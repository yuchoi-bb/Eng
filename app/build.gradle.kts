/**
 * 버전은 루트의 VERSION 파일 하나로 정한다.
 *
 * 릴리즈 워크플로가 같은 파일에서 태그를 만들므로, 기기에 깔린 앱의 versionName과
 * GitHub 릴리즈의 태그가 항상 같은 값을 가리킨다. 인앱 업데이트 확인이 그 일치에 의존한다.
 */
val appVersionName: String = rootProject.file("VERSION").readText().trim().removePrefix("v")

/** core의 AppVersion.versionCode와 같은 규칙. 둘이 어긋나면 업데이트 판단이 흔들린다. */
val appVersionCode: Int = appVersionName.substringBefore('-').split('.').let { parts ->
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    (major * 1_000_000 + minor.coerceAtMost(999) * 1_000 + patch.coerceAtMost(999)).coerceAtLeast(1)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.eng.shadowing"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eng.shadowing"
        minSdk = 26          // Photo Picker 백포트와 MediaExtractor 사용 범위
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // 인앱 업데이트가 릴리즈를 조회할 저장소. 공개 저장소라 토큰이 필요 없다.
        buildConfigField("String", "UPDATE_REPO", "\"yuchoi-bb/Eng\"")
    }

    /**
     * 레포에 담긴 고정 디버그 키스토어로 서명한다.
     *
     * AGP가 기본으로 쓰는 ~/.android/debug.keystore는 기기·CI 러너마다 새로 생성되므로,
     * 빌드할 때마다 서명이 달라진다. 그러면 새 APK를 덮어쓰기로 설치할 수 없고
     * (INSTALL_FAILED_UPDATE_INCOMPATIBLE) 지웠다 깔아야 하는데, 그때 학습 기록도 함께 날아간다.
     *
     * 이 키는 비밀이 아니다 — 비밀번호가 공개된 표준 안드로이드 디버그 자격증명이고,
     * 사이드로드 설치에만 쓴다. **Play Store 업로드에는 쓸 수 없다.**
     */
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // S0는 사이드로드 배포다. 릴리즈 빌드도 같은 키로 서명해 설치 가능한 APK를 낸다.
            // 실제 업로드 키는 Play Console 배포를 붙이는 S1에서 별도로 만든다.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
}

dependencies {
    // 학습 루프의 계산과 상태 기계는 전부 :core에 있다. 이 모듈은 그 위의 껍데기다.
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.youtube.player)
}
