import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.mynavi"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.mynavi"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "KAKAO_NATIVE_KEY", "\"${localProps.getProperty("KAKAO_NATIVE_KEY", "")}\"")
        // 서버 주소: 네트워크마다 달라서 local.properties의 API_BASE_URL로 지정 (없으면 기본값)
        buildConfigField("String", "API_BASE_URL", "\"${localProps.getProperty("API_BASE_URL", "http://165.246.31.189:8080/")}\"")
        // 주행 기록 재생 모드: 폰 GPS 대신 서버의 주행 기록(GET /api/replay)으로 달린다. local.properties 에 REPLAY_MODE=true
        buildConfigField("boolean", "REPLAY_MODE", localProps.getProperty("REPLAY_MODE", "false").trim().toBoolean().toString())
        // 재생 속도 배율 (1 = 실제 속도, 2 = 두 배 빠르게)
        buildConfigField("float", "REPLAY_SPEED", "${localProps.getProperty("REPLAY_SPEED", "1").trim().toFloatOrNull() ?: 1f}f")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("com.kakao.maps.open:android:2.15.2")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}