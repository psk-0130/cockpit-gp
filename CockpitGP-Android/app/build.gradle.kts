plugins {
    id("com.android.application")
}

android {
    namespace = "com.cockpitgp.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cockpitgp.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.22"))
}
