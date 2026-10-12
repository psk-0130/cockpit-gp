# PuTTY에 붙여넣을 명령어 (학교 리눅스 서버)

PuTTY에서는 **마우스 오른쪽 클릭 = 붙여넣기**입니다. 아래 회색 상자를 하나씩 복사해서 붙여넣고 Enter를 누르세요.

## 0. 접속
PuTTY 실행 → Host Name에 **서버 IP**, Port **22**, SSH 선택 → Open → 아이디/비밀번호 입력
(비밀번호는 입력해도 화면에 안 보이는 게 정상입니다)

## 1. 작업 폴더 만들기
```bash
mkdir -p ~/cockpitgp && cd ~/cockpitgp && pwd
```

## 2. Node 버전 확인
```bash
node -v
```
- **v22.13 이상** → 3단계로
- **그보다 낮음** → 3단계 후에 `npm install better-sqlite3` 실행
- **command not found** → Node가 없습니다. 서버 관리자에게 Node.js 22 이상 설치를 요청하세요.

## 3. server.js 만들기 (아래 상자 **전체**를 한 번에 복사해서 붙여넣기)
```bash
cat > ~/cockpitgp/server.js <<'COCKPITGP_EOF'
#!/usr/bin/env node
/*
 * Cockpit GP 기록 서버 (Node.js 버전)
 *
 * SQLite 모듈 (둘 중 하나):
 *   - Node 22.13 이상: 내장 node:sqlite 를 사용 → 설치할 것 없음
 *   - 그보다 낮은 버전: 같은 폴더에서  npm install better-sqlite3
 *
 * 실행:  node server.js                       (기본: 0.0.0.0:8000, DB 파일 cockpitgp.db)
 *        node server.js --port 9000 --db /home/me/cockpitgp.db
 *
 * API
 *   GET  /api/health
 *   POST /api/laps        {player, track, car, mode, lap_no, lap_ms, valid}
 *   POST /api/races       {player, track, car, laps, difficulty, position, total_ms, penalty_s, best_lap_ms}
 *   POST /api/puzzles     {player, type, correct, ms, mode, track}      수리 퍼즐 한 문제의 결과
 *   GET  /api/leaderboard?track=monza&car=f1&limit=10
 *   GET  /api/races?player=이름&limit=20
 *   GET  /api/puzzles/stats?player=이름                                  퍼즐 유형별 정답률·평균 반응 시간
 *
 * 실시간 멀티플레이: ws://서버IP:8000/ws
 *   WebSocket 을 이 파일이 직접 처리하므로 추가 설치(npm install ws 등)가 필요 없습니다.
 */
'use strict';
const http = require('http');
const crypto = require('crypto');

const argv = process.argv.slice(2);
const opt = (name, def) => { const i = argv.indexOf('--' + name); return i >= 0 && argv[i + 1] ? argv[i + 1] : def; };
const PORT = Number(opt('port', 8000));
const HOST = opt('host', '0.0.0.0');
const DB_PATH = opt('db', 'cockpitgp.db');

// node:sqlite (내장) 와 better-sqlite3 는 prepare().run/get/all 사용법이 같습니다.
let Database, driver;
try { ({ DatabaseSync: Database } = require('node:sqlite')); driver = 'node:sqlite'; }
catch (e) {
  try { Database = require('better-sqlite3'); driver = 'better-sqlite3'; }
  catch (e2) {
    console.error('SQLite 모듈을 찾을 수 없습니다. Node 22.13 이상을 쓰거나, 이 폴더에서 "npm install better-sqlite3" 를 실행하세요.');
    process.exit(1);
  }
}

const db = new Database(DB_PATH);
db.exec(`
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS players (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL UNIQUE,
    created_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE TABLE IF NOT EXISTS laps (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    player_id   INTEGER NOT NULL REFERENCES players(id),
    track       TEXT NOT NULL,              -- monza | spa | rbr | interlagos
    car         TEXT NOT NULL,              -- f1 | gt
    mode        TEXT NOT NULL,              -- tt (타임 트라이얼) | race (AI 레이스) | multi (멀티플레이)
    lap_no      INTEGER,
    lap_ms      INTEGER NOT NULL,           -- 랩 타임(밀리초)
    valid       INTEGER NOT NULL DEFAULT 1, -- 트랙 리밋 위반 시 0
    created_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_laps_board ON laps (track, car, valid, lap_ms);
CREATE TABLE IF NOT EXISTS races (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    player_id   INTEGER NOT NULL REFERENCES players(id),
    track       TEXT NOT NULL,
    car         TEXT NOT NULL,
    laps        INTEGER NOT NULL,
    difficulty  TEXT NOT NULL,              -- easy | normal | hard | multi (멀티플레이)
    position    INTEGER NOT NULL,           -- 최종 순위 (1~8)
    total_ms    INTEGER NOT NULL,           -- 완주 기록(밀리초)
    penalty_s   INTEGER NOT NULL DEFAULT 0, -- (이전 버전의 고정 수리 페널티, 지금은 0)
    best_lap_ms INTEGER,
    created_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE TABLE IF NOT EXISTS puzzles (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    player_id   INTEGER NOT NULL REFERENCES players(id),
    type        TEXT NOT NULL,              -- stroop (좌뇌형: 스트룹) | odd (우뇌형: 다른 하나 찾기)
    correct     INTEGER NOT NULL,           -- 1 정답 / 0 오답
    ms          INTEGER NOT NULL,           -- 문제가 나온 뒤 답을 누르기까지(밀리초)
    mode        TEXT NOT NULL,
    track       TEXT NOT NULL,
    created_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
`);

const TRACKS = new Set(['monza', 'spa', 'rbr', 'interlagos']);
const CARS = new Set(['f1', 'gt']);
const MODES = new Set(['tt', 'race', 'multi']);
const DIFFS = new Set(['easy', 'normal', 'hard', 'multi']);
const PUZZLES = new Set(['stroop', 'odd']);

const sql = {
  insPlayer: db.prepare('INSERT OR IGNORE INTO players (name) VALUES (?)'),
  getPlayer: db.prepare('SELECT id FROM players WHERE name = ?'),
  insLap: db.prepare('INSERT INTO laps (player_id, track, car, mode, lap_no, lap_ms, valid) VALUES (?, ?, ?, ?, ?, ?, ?)'),
  insRace: db.prepare(`INSERT INTO races (player_id, track, car, laps, difficulty, position, total_ms, penalty_s, best_lap_ms)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`),
  insPuzzle: db.prepare('INSERT INTO puzzles (player_id, type, correct, ms, mode, track) VALUES (?, ?, ?, ?, ?, ?)'),
  board: db.prepare(`SELECT p.name AS player, MIN(l.lap_ms) AS best_ms, COUNT(*) AS laps, MAX(l.created_at) AS at
                     FROM laps l JOIN players p ON p.id = l.player_id
                     WHERE l.track = ? AND l.car = ? AND l.valid = 1
                     GROUP BY p.id ORDER BY best_ms ASC LIMIT ?`),
  races: db.prepare(`SELECT r.id, p.name AS player, r.track, r.car, r.laps, r.difficulty, r.position, r.total_ms,
                            r.penalty_s, r.best_lap_ms, r.created_at
                     FROM races r JOIN players p ON p.id = r.player_id ORDER BY r.id DESC LIMIT ?`),
  racesBy: db.prepare(`SELECT r.id, p.name AS player, r.track, r.car, r.laps, r.difficulty, r.position, r.total_ms,
                              r.penalty_s, r.best_lap_ms, r.created_at
                       FROM races r JOIN players p ON p.id = r.player_id WHERE p.name = ? ORDER BY r.id DESC LIMIT ?`),
  puzzleStats: db.prepare(`SELECT type, COUNT(*) AS n, SUM(correct) AS right_n,
                                  AVG(CASE WHEN correct = 1 THEN ms END) AS avg_ms, MIN(CASE WHEN correct = 1 THEN ms END) AS best_ms
                           FROM puzzles GROUP BY type`),
  puzzleStatsBy: db.prepare(`SELECT q.type, COUNT(*) AS n, SUM(q.correct) AS right_n,
                                    AVG(CASE WHEN q.correct = 1 THEN q.ms END) AS avg_ms, MIN(CASE WHEN q.correct = 1 THEN q.ms END) AS best_ms
                             FROM puzzles q JOIN players p ON p.id = q.player_id WHERE p.name = ? GROUP BY q.type`),
  count: (table) => Number(db.prepare(`SELECT COUNT(*) AS n FROM ${table}`).get().n),
};

class BadRequest extends Error {}

function text(body, key, allowed, maxLen = 20) {
  const v = body[key];
  if (typeof v !== 'string' || !v.trim()) throw new BadRequest(`'${key}' 값이 필요합니다`);
  const s = v.trim().slice(0, maxLen);
  if (allowed && !allowed.has(s)) throw new BadRequest(`'${key}' 값이 올바르지 않습니다: ${s}`);
  return s;
}

function int(body, key, lo, hi, required = true) {
  const v = body[key];
  if (v === undefined || v === null) {
    if (!required) return null;
    throw new BadRequest(`'${key}' 값이 필요합니다`);
  }
  if (typeof v !== 'number' || !Number.isFinite(v)) throw new BadRequest(`'${key}' 는 숫자여야 합니다`);
  const n = Math.trunc(v);
  if (n < lo || n > hi) throw new BadRequest(`'${key}' 값이 범위를 벗어났습니다 (${lo}~${hi})`);
  return n;
}

function playerId(name) {
  sql.insPlayer.run(name);
  return sql.getPlayer.get(name).id;
}

function addLap(b) {
  const name = text(b, 'player');
  const row = [text(b, 'track', TRACKS), text(b, 'car', CARS), text(b, 'mode', MODES),
    int(b, 'lap_no', 0, 1000, false), int(b, 'lap_ms', 5000, 3600000), b.valid === undefined || b.valid ? 1 : 0];
  const r = sql.insLap.run(playerId(name), ...row);
  return { ok: true, id: Number(r.lastInsertRowid) };
}

function addRace(b) {
  const name = text(b, 'player');
  const row = [text(b, 'track', TRACKS), text(b, 'car', CARS), int(b, 'laps', 1, 100), text(b, 'difficulty', DIFFS),
    int(b, 'position', 1, 8), int(b, 'total_ms', 5000, 36000000), int(b, 'penalty_s', 0, 3600, false) || 0,
    int(b, 'best_lap_ms', 5000, 3600000, false)];
  const r = sql.insRace.run(playerId(name), ...row);
  return { ok: true, id: Number(r.lastInsertRowid) };
}

function addPuzzle(b) {
  const name = text(b, 'player');
  const row = [text(b, 'type', PUZZLES), b.correct ? 1 : 0, int(b, 'ms', 0, 600000), text(b, 'mode', MODES), text(b, 'track', TRACKS)];
  const r = sql.insPuzzle.run(playerId(name), ...row);
  return { ok: true, id: Number(r.lastInsertRowid) };
}

// { stroop: {n, accuracy, avg_ms, best_ms}, odd: {...} }
function shapeStats(rows) {
  const out = {};
  for (const r of rows) {
    const n = Number(r.n), right = Number(r.right_n || 0);
    out[r.type] = { n, accuracy: n ? right / n : 0, avg_ms: r.avg_ms == null ? null : Math.round(Number(r.avg_ms)),
      best_ms: r.best_ms == null ? null : Number(r.best_ms) };
  }
  return out;
}

function puzzleStats(q) {
  const player = q.get('player');
  return { all: shapeStats(sql.puzzleStats.all()), me: player ? shapeStats(sql.puzzleStatsBy.all(player)) : null };
}

function leaderboard(q) {
  const track = q.get('track') || 'monza', car = q.get('car') || 'f1';
  if (!TRACKS.has(track) || !CARS.has(car)) throw new BadRequest('track 또는 car 값이 올바르지 않습니다');
  const limit = Math.max(1, Math.min(50, parseInt(q.get('limit'), 10) || 10));
  return { track, car, rows: sql.board.all(track, car, limit) };
}

function recentRaces(q) {
  const limit = Math.max(1, Math.min(100, parseInt(q.get('limit'), 10) || 20));
  const player = q.get('player');
  return { rows: player ? sql.racesBy.all(player, limit) : sql.races.all(limit) };
}

function health() {
  return { ok: true, laps: sql.count('laps'), races: sql.count('races'), players: sql.count('players'),
    puzzles: sql.count('puzzles'), rooms: rooms.size, multiplayer: true };
}

function send(res, status, payload) {
  const data = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(data),
    // 앱(WebView)이 file:// 에서 열린 페이지로 요청하므로 CORS 허용이 필요합니다.
    'Access-Control-Allow-Origin': '*',
  });
  res.end(data);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > 10000) { reject(new BadRequest('요청이 너무 큽니다')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      try {
        const body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
        if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error();
        resolve(body);
      } catch (e) { reject(new BadRequest('JSON 객체가 필요합니다')); }
    });
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
      'Access-Control-Max-Age': '86400',
    });
    return res.end();
  }
  const url = new URL(req.url, 'http://localhost');
  try {
    if (req.method === 'GET' && url.pathname === '/api/health') return send(res, 200, health());
    if (req.method === 'GET' && url.pathname === '/api/leaderboard') return send(res, 200, leaderboard(url.searchParams));
    if (req.method === 'GET' && url.pathname === '/api/races') return send(res, 200, recentRaces(url.searchParams));
    if (req.method === 'GET' && url.pathname === '/api/puzzles/stats') return send(res, 200, puzzleStats(url.searchParams));
    const posts = { '/api/laps': addLap, '/api/races': addRace, '/api/puzzles': addPuzzle };
    if (req.method === 'POST' && posts[url.pathname]) {
      const body = await readJson(req);
      return send(res, 201, posts[url.pathname](body));
    }
    send(res, 404, { error: '없는 주소입니다' });
  } catch (e) {
    if (e instanceof BadRequest) send(res, 400, { error: e.message });
    else { console.error(e); send(res, 500, { error: `서버 오류: ${e.message}` }); }
  }
});

/* ============================================================================================
 * WebSocket (RFC 6455) — 텍스트 메시지만 쓰는 최소 구현. 브라우저/WebView 의 new WebSocket() 과 통신합니다.
 * ========================================================================================== */
const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

class WsClient {
  constructor(socket) {
    this.sock = socket; this.buf = Buffer.alloc(0); this.frag = []; this.open = true;
    this.onmessage = null; this.onclose = null;
    socket.setNoDelay(true);
    socket.on('data', (d) => this.onData(d));
    socket.on('close', () => this.close());
    socket.on('error', () => this.close());
  }
  onData(d) {
    this.buf = Buffer.concat([this.buf, d]);
    while (this.buf.length >= 2) {
      const b0 = this.buf[0], b1 = this.buf[1], fin = (b0 & 0x80) !== 0, op = b0 & 0x0f, masked = (b1 & 0x80) !== 0;
      let len = b1 & 0x7f, off = 2;
      if (len === 126) { if (this.buf.length < 4) return; len = this.buf.readUInt16BE(2); off = 4; }
      else if (len === 127) {
        if (this.buf.length < 10) return;
        if (this.buf.readUInt32BE(2) !== 0) return this.close();
        len = this.buf.readUInt32BE(6); off = 10;
      }
      if (len > 1 << 20) return this.close();               // 1MB 넘는 메시지는 거부
      const mlen = masked ? 4 : 0;
      if (this.buf.length < off + mlen + len) return;       // 프레임이 아직 다 안 옴
      let payload = Buffer.from(this.buf.subarray(off + mlen, off + mlen + len));
      if (masked) { const m = this.buf.subarray(off, off + 4); for (let i = 0; i < payload.length; i++) payload[i] ^= m[i & 3]; }
      this.buf = this.buf.subarray(off + mlen + len);
      if (op === 0x8) { this.sendRaw(0x8, Buffer.alloc(0)); return this.close(); }   // close
      if (op === 0x9) { this.sendRaw(0xA, payload); continue; }                          // ping → pong
      if (op === 0xA) continue;                                                           // pong
      if (op === 0x1 || op === 0x0) {                                                     // text (+continuation)
        this.frag.push(payload);
        if (fin) {
          const msg = Buffer.concat(this.frag).toString('utf8'); this.frag = [];
          if (this.onmessage) { try { this.onmessage(msg); } catch (e) { console.error(e); } }
        }
      }
    }
  }
  sendRaw(op, payload) {
    if (!this.open) return;
    const len = payload.length; let hdr;
    if (len < 126) hdr = Buffer.from([0x80 | op, len]);
    else if (len < 65536) { hdr = Buffer.alloc(4); hdr[0] = 0x80 | op; hdr[1] = 126; hdr.writeUInt16BE(len, 2); }
    else { hdr = Buffer.alloc(10); hdr[0] = 0x80 | op; hdr[1] = 127; hdr.writeUInt32BE(0, 2); hdr.writeUInt32BE(len, 6); }
    try { this.sock.write(Buffer.concat([hdr, payload])); } catch (e) { this.close(); }
  }
  send(obj) { this.sendRaw(0x1, Buffer.from(JSON.stringify(obj))); }
  close() {
    if (!this.open) return;
    this.open = false;
    try { this.sock.end(); } catch (e) { /* already closed */ }
    if (this.onclose) this.onclose();
  }
}

server.on('upgrade', (req, socket) => {
  const url = new URL(req.url, 'http://localhost');
  const key = req.headers['sec-websocket-key'];
  if (url.pathname !== '/ws' || String(req.headers.upgrade || '').toLowerCase() !== 'websocket' || !key) {
    socket.end('HTTP/1.1 400 Bad Request\r\n\r\n'); return;
  }
  const accept = crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
  socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n' +
    `Sec-WebSocket-Accept: ${accept}\r\n\r\n`);
  onConnect(new WsClient(socket));
});

/* ============================================================================================
 * 멀티플레이 방
 *   클라이언트 → 서버: ping, create, join, leave, settings, start, state, finish
 *   서버 → 클라이언트: hello, pong, room, start, states(초당 15번), finished, results, error
 *   - 각 폰은 자기 차만 계산하고 위치를 보냅니다. 서버는 모아서 다른 폰에 전달합니다.
 *   - 출발 시각과 완주 기록은 서버 시계로 정합니다(공정성).
 * ========================================================================================== */
const rooms = new Map();
const MAX_PLAYERS = 8;
const FINISH_GRACE_MS = 60000;   // 1등이 들어온 뒤 나머지를 기다리는 시간
const COLORS = [0xe10600, 0x1e5bd8, 0xf28c13, 0x16a37a, 0xe8e8e8, 0x7a2fd0, 0xf2d21b, 0x26c6da];
let nextId = 1;

function newCode() { let c; do { c = String(Math.floor(1000 + Math.random() * 9000)); } while (rooms.has(c)); return c; }

function cleanSettings(s) {
  s = s && typeof s === 'object' ? s : {};
  const laps = Math.trunc(Number(s.laps));
  return {
    track: TRACKS.has(s.track) ? s.track : 'monza',
    car: CARS.has(s.car) ? s.car : 'f1',
    laps: laps >= 1 && laps <= 20 ? laps : 3,
  };
}

function cleanName(n) { return typeof n === 'string' && n.trim() ? n.trim().slice(0, 20) : `플레이어-${Math.floor(1000 + Math.random() * 9000)}`; }

function roomView(r) {
  return { code: r.code, host: r.host, phase: r.phase, settings: r.settings,
    players: [...r.players.values()].map((p) => ({ id: p.id, name: p.name, color: p.color })) };
}

function bcast(r, obj) { const data = Buffer.from(JSON.stringify(obj)); for (const p of r.players.values()) p.ws.sendRaw(0x1, data); }
function pushRoom(r) { bcast(r, { t: 'room', room: roomView(r) }); }

function freeColor(r) { const used = new Set([...r.players.values()].map((p) => p.color)); return COLORS.find((c) => !used.has(c)) || COLORS[0]; }

function uniqueName(r, name) {
  const names = new Set([...r.players.values()].map((p) => p.name));
  if (!names.has(name)) return name;
  for (let k = 2; ; k++) if (!names.has(`${name}(${k})`)) return `${name}(${k})`;
}

function addToRoom(r, me, name) {
  me.room = r;
  me.player = { id: me.id, name: uniqueName(r, name), color: freeColor(r), ws: me.ws, state: null, finished: false, totalMs: 0, bestLapMs: null };
  r.players.set(me.id, me.player);
  pushRoom(r);
}

function leaveRoom(me) {
  const r = me.room; if (!r) return;
  r.players.delete(me.id); me.room = null; me.player = null;
  if (!r.players.size) { clearTimeout(r.endTimer); rooms.delete(r.code); return; }
  if (r.host === me.id) r.host = r.players.keys().next().value;     // 방장이 나가면 다음 사람이 방장
  pushRoom(r);
  if (r.phase === 'race') checkEnd(r);
}

function startRace(r) {
  const now = Date.now();
  r.phase = 'race';
  r.T0 = now + 1500;                                     // 첫 번째 빨간불이 켜지는 시각 기준
  r.startAt = r.T0 + 5000 + 400 + Math.floor(Math.random() * 2000);   // 5개 다 켜진 뒤 0.4~2.4초 후 소등
  r.firstFinish = 0; clearTimeout(r.endTimer);
  const grid = [...r.players.values()].map((p, slot) => {
    Object.assign(p, { state: null, finished: false, totalMs: 0, bestLapMs: null });
    return { id: p.id, slot };
  });
  bcast(r, { t: 'start', T0: r.T0, startAt: r.startAt, settings: r.settings, grid, room: roomView(r) });
}

function checkEnd(r) {
  if (r.phase !== 'race') return;
  const ps = [...r.players.values()];
  if (ps.length && ps.every((p) => p.finished)) endRace(r);
}

function endRace(r) {
  if (r.phase !== 'race') return;
  clearTimeout(r.endTimer);
  const ps = [...r.players.values()];
  const fin = ps.filter((p) => p.finished).sort((a, b) => a.totalMs - b.totalMs);
  const dnf = ps.filter((p) => !p.finished).sort((a, b) => ((b.state && b.state.dist) || 0) - ((a.state && a.state.dist) || 0));
  const rows = [...fin, ...dnf].map((p, k) => ({ id: p.id, name: p.name, color: p.color, pos: k + 1,
    totalMs: p.finished ? p.totalMs : null, bestLapMs: p.bestLapMs, dnf: !p.finished }));
  for (const row of rows) {
    if (row.dnf) continue;
    try {
      sql.insRace.run(playerId(row.name), r.settings.track, r.settings.car, r.settings.laps, 'multi', row.pos,
        Math.max(5000, row.totalMs), 0, row.bestLapMs && row.bestLapMs >= 5000 ? row.bestLapMs : null);
    } catch (e) { console.error('결과 저장 실패', e.message); }
  }
  r.phase = 'lobby';
  bcast(r, { t: 'results', rows, settings: r.settings, room: roomView(r) });
}

function onConnect(ws) {
  const me = { id: nextId++, ws, room: null, player: null };
  ws.send({ t: 'hello', id: me.id, now: Date.now() });
  ws.onclose = () => leaveRoom(me);
  ws.onmessage = (raw) => {
    let m; try { m = JSON.parse(raw); } catch (e) { return; }
    if (!m || typeof m !== 'object') return;
    const r = me.room;
    const err = (msg) => ws.send({ t: 'error', msg });
    switch (m.t) {
      case 'ping': ws.send({ t: 'pong', c: m.c, now: Date.now() }); break;
      case 'create': {
        leaveRoom(me);
        const room = { code: newCode(), host: me.id, phase: 'lobby', settings: cleanSettings(m.settings), players: new Map() };
        rooms.set(room.code, room);
        addToRoom(room, me, cleanName(m.name));
        break;
      }
      case 'join': {
        const room = rooms.get(String(m.code || '').trim());
        if (!room) return err('그 코드의 방이 없습니다. 코드를 다시 확인하세요.');
        if (room.phase !== 'lobby') return err('이미 레이스가 진행 중인 방입니다. 끝난 뒤 다시 참가하세요.');
        if (room.players.size >= MAX_PLAYERS) return err(`방이 가득 찼습니다 (최대 ${MAX_PLAYERS}명).`);
        leaveRoom(me);
        addToRoom(room, me, cleanName(m.name));
        break;
      }
      case 'leave': leaveRoom(me); ws.send({ t: 'room', room: null }); break;
      case 'settings':
        if (!r || r.host !== me.id || r.phase !== 'lobby') return;
        r.settings = cleanSettings(m.settings); pushRoom(r); break;
      case 'start':
        if (!r) return;
        if (r.host !== me.id) return err('방장만 레이스를 시작할 수 있습니다.');
        if (r.phase !== 'lobby') return;
        startRace(r); break;
      case 'state': {
        if (!r || r.phase !== 'race' || !me.player) return;
        const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : 0);
        me.player.state = { x: num(m.x), z: num(m.z), h: num(m.h), v: num(m.v), s: num(m.s), d: num(m.d),
          dist: num(m.dist), lap: num(m.lap), dmg: num(m.dmg), rep: !!m.rep };
        break;
      }
      case 'finish': {
        if (!r || r.phase !== 'race' || !me.player || me.player.finished) return;
        const now = Date.now();
        me.player.finished = true;
        me.player.totalMs = Math.max(0, now - r.startAt);          // 서버 시계 기준 완주 기록
        me.player.bestLapMs = typeof m.bestLapMs === 'number' && Number.isFinite(m.bestLapMs) ? Math.round(m.bestLapMs) : null;
        bcast(r, { t: 'finished', id: me.id, name: me.player.name, totalMs: me.player.totalMs });
        if (!r.firstFinish) { r.firstFinish = now; r.endTimer = setTimeout(() => endRace(r), FINISH_GRACE_MS); }
        checkEnd(r);
        break;
      }
      default: break;
    }
  };
}

// 진행 중인 방마다 모든 플레이어의 위치를 초당 15번 전달
setInterval(() => {
  const now = Date.now();
  for (const r of rooms.values()) {
    if (r.phase !== 'race') continue;
    const list = [];
    for (const p of r.players.values()) if (p.state) list.push({ id: p.id, fin: p.finished, ...p.state });
    if (list.length) bcast(r, { t: 'states', now, list });
  }
}, 66);

server.listen(PORT, HOST, () => {
  console.log(`Cockpit GP 기록 서버 실행 중: http://${HOST}:${PORT}  (DB: ${DB_PATH}, ${driver})  멀티플레이: ws://${HOST}:${PORT}/ws  종료: Ctrl+C`);
});
COCKPITGP_EOF
ls -l ~/cockpitgp/server.js
```
마지막 줄에 `server.js` 파일 크기(약 23KB)가 보이면 성공입니다.

## 4. 서버 실행
```bash
cd ~/cockpitgp && node server.js --port 8000
```
`Cockpit GP 기록 서버 실행 중: http://0.0.0.0:8000` 이 나오면 성공. (종료: Ctrl+C)

**PuTTY 창을 닫아도 계속 실행하려면** (Ctrl+C로 끈 뒤)
```bash
cd ~/cockpitgp && nohup node server.js --port 8000 > server.log 2>&1 &
```
끌 때: `pkill -f "node server.js"`

## 5. 접속 확인 (PuTTY 창을 하나 더 열어서)
```bash
curl http://localhost:8000/api/health
hostname -I
```
- `{"ok":true,...}` → 서버 정상
- `hostname -I` 의 첫 번째 주소가 **서버 IP** → 게임의 서버 주소에 `http://그IP:8000` 입력

## 6. 휴대폰/에뮬레이터에서 접속이 안 될 때
```bash
sudo ufw status
sudo ufw allow 8000/tcp
```
(가상서버라면 가상머신의 포트 포워딩 또는 보안 그룹에서 8000번 포트도 열어야 합니다)

## 7. DB 안 들여다보기
```bash
cd ~/cockpitgp
sqlite3 cockpitgp.db ".headers on" ".mode column" "SELECT * FROM players;" "SELECT id, player_id, track, car, lap_ms, valid, created_at FROM laps ORDER BY id DESC LIMIT 10;" "SELECT * FROM races ORDER BY id DESC LIMIT 10;"
```
`sqlite3: command not found` 이면: `sudo apt install sqlite3`