import java.util.Properties

plugins {
    id("com.android.application")
}

// 에어코리아 API 인증키는 local.properties의 AIRKOREA_KEY 에 넣는다 (Git에 올라가지 않음)
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.sumcare.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sumcare.app"
        minSdk = 28          // Pleos 요구사항
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "AIRKOREA_KEY", "\"${localProps.getProperty("AIRKOREA_KEY", "")}\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation("ai.pleos.playground:Vehicle:2.3.2")        // 창문·공조·기어·착석
    implementation("ai.pleos.playground:NaviHelper:3.0.5")     // 현재 위치
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")   // 음성 안내 (ON_DEVICE)
    implementation("ai.pleos.playground:SpeechToText:3.3.1")   // 음성 명령
}
