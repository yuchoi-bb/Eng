rootProject.name = "myna"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

include(":core")

// :app은 Android SDK가 있어야 설정(configure)조차 된다.
// SDK가 없는 환경(CI의 로직 검증 잡, 이 저장소의 개발 컨테이너)에서도
// :core 테스트는 돌아가야 하므로 조건부로 포함한다.
val androidSdkPresent =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (androidSdkPresent) {
    include(":app")
} else {
    gradle.rootProject {
        logger.lifecycle(
            "[eng] Android SDK를 찾지 못해 :app 모듈을 제외했습니다. " +
                ":core 로직과 테스트는 그대로 사용할 수 있습니다.",
        )
    }
}
