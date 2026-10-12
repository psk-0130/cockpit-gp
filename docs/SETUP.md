# 설치 · 실행 가이드

```
[안드로이드 앱]                        [리눅스 서버]
 WebView ─ cockpit-gp.html ── HTTP / WebSocket ──▶ server.js ── cockpitgp.db (SQLite)
          (게임)                                  (기록 API, 멀티플레이)
```

SQLite는 **서버 안에 있는 파일**이라 휴대폰이 직접 접속할 수 없습니다.
서버에서 `server.js`가 DB 파일을 대신 읽고 쓰고, 게임은 `server.js`에 요청을 보냅니다.

## 1단계: 서버 실행 (Node.js)

```bash
node -v                         # 22.13 이상이면 내장 SQLite 사용 → 설치할 것 없음
npm install better-sqlite3      # 22.13 미만일 때만, server.js 가 있는 폴더에서 실행
node server.js --port 8000      # DB 파일 cockpitgp.db 는 처음 실행할 때 자동 생성
hostname -I                     # 서버 IP 확인
```

터미널을 닫아도 계속 실행하려면:

```bash
setsid nohup node server.js --port 8000 > server.log 2>&1 < /dev/null &
pkill -f "node server.js"       # 끌 때
```

접속 확인:

```bash
curl http://서버IP:8000/api/health
# {"ok":true,"laps":0,"races":0,"players":0,...} 가 나오면 성공
```

**접속이 안 되면**
- 방화벽: `sudo ufw allow 8000/tcp`
- 가상서버라면 포트 포워딩·보안 그룹에서 8000번 포트 허용
- 휴대폰(또는 에뮬레이터를 실행하는 PC)이 서버와 **같은 네트워크**에 있어야 함

PuTTY로 접속해 붙여넣기만으로 서버를 만드는 방법은 [`PuTTY-명령어.md`](../PuTTY-명령어.md)에 있습니다.

## 2단계: 안드로이드 앱

1. Android Studio에서 **Open** → `CockpitGP-Android` 폴더 선택 → Gradle 동기화
2. ▶ 실행 (에뮬레이터 또는 USB 디버깅을 켠 폰)
3. 게임 파일은 `app/src/main/assets/cockpit-gp.html` 입니다. 루트의 `cockpit-gp.html`을 고친 뒤 이 위치로 복사하세요.

> 게임은 3D 라이브러리와 글꼴을 인터넷(CDN)에서 불러옵니다. 휴대폰이나 에뮬레이터가 인터넷에 연결돼 있어야 화면이 뜹니다.

## 3단계: 게임에서 서버 연결

1. 게임 실행 → **게임 시작** → 레이스 설정 화면 아래 **기록 저장 · DB 서버**
2. **플레이어 이름** 입력, **서버 주소**에 `http://서버IP:8000` 입력
3. **연결 확인** → "연결됨 · DB에 랩 기록 N개…"가 나오면 완료

자주 쓰는 서버가 있다면 `cockpit-gp.html`의 `DEFAULT_URL`에 주소를 넣어 두면 처음 실행할 때부터 그 서버로 연결됩니다.

이후 자동으로 저장됩니다.
- 랩을 끝낼 때마다 → `laps` (트랙 리밋 위반 랩은 `valid = 0`)
- AI 레이스·멀티플레이를 완주하면 → `races`
- 수리 퍼즐을 풀 때마다 → `puzzles`
- 처음 화면 **기록 순위** → 서킷·차량별 베스트 랩 Top 10 + 수리 퍼즐 통계

## 멀티플레이

1. 모든 폰에서 **같은 서버 주소** 입력
2. 방장: 모드 **멀티플레이** → 서킷·차량·랩 수 고르기 → **멀티플레이 로비** → **방 만들기** → **4자리 방 코드**를 친구에게 알려 주기
3. 나머지: **멀티플레이 로비** → 방 코드 입력 → **참가**
4. 방장이 **레이스 시작** → 모든 폰에서 빨간불이 동시에 꺼짐
5. 모두 완주하면(또는 1등 완주 60초 뒤) 결과표, 서버가 `races`에 `difficulty = 'multi'`로 저장

## DB 구조 (SQLite)

```sql
players(id, name UNIQUE, created_at)
laps   (id, player_id → players.id, track, car, mode, lap_no, lap_ms, valid, created_at)
races  (id, player_id → players.id, track, car, laps, difficulty, position,
        total_ms, penalty_s, best_lap_ms, created_at)
puzzles(id, player_id → players.id, type, correct, ms, mode, track, created_at)
```

- 시간은 모두 **밀리초(ms)**. 예: `93210` = 1:33.210
- `track`: `monza` `spa` `rbr` `interlagos` / `car`: `f1` `gt` / `mode`: `tt` `race` `multi`
- `puzzles.type`: `stroop`(좌뇌형) `odd`(우뇌형) / `correct`: 1 정답, 0 오답

좌뇌형·우뇌형 비교:

```bash
sqlite3 cockpitgp.db "SELECT type, COUNT(*) AS 문제수, ROUND(AVG(correct)*100,1) AS 정답률, ROUND(AVG(CASE WHEN correct=1 THEN ms END)) AS 평균ms FROM puzzles GROUP BY type;"
```

## API

| 메서드 | 주소 | 설명 |
|---|---|---|
| GET | `/api/health` | 서버 상태, 저장된 개수 |
| POST | `/api/laps` | 랩 기록 `{player, track, car, mode, lap_no, lap_ms, valid}` |
| POST | `/api/races` | 레이스 결과 `{player, track, car, laps, difficulty, position, total_ms, penalty_s, best_lap_ms}` |
| POST | `/api/puzzles` | 수리 퍼즐 결과 `{player, type, correct, ms, mode, track}` |
| GET | `/api/leaderboard?track=monza&car=f1&limit=10` | 플레이어별 베스트 랩 순위 |
| GET | `/api/races?player=이름&limit=20` | 최근 레이스 결과 |
| GET | `/api/puzzles/stats?player=이름` | 퍼즐 유형별 정답률·평균 반응 시간 |
| WebSocket | `ws://서버IP:8000/ws` | 멀티플레이 |

## 문제 해결

| 증상 | 확인할 것 |
|---|---|
| 연결 확인 → "응답 시간 초과" | 서버 IP·포트, 서버 실행 여부, 방화벽, 같은 네트워크인지 |
| 연결 확인 → "Failed to fetch" | 매니페스트의 `INTERNET` 권한과 `usesCleartextTraffic="true"` |
| 멀티플레이 로비 → "서버에 연결하지 못했습니다" | 서버를 최신 `server.js`로 다시 실행했는지 |
| 앱 화면이 검게만 나옴 | 휴대폰·에뮬레이터 인터넷 연결 (3D 라이브러리를 CDN에서 불러옴) |
| 기록이 저장 안 됨 | 플레이어 이름과 서버 주소를 입력했는지 |
