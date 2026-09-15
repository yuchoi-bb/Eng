plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 순수 Kotlin/JVM 모듈. Android 의존이 없어야 한다 —
// REQUIREMENTS §9.4가 "산술은 LLM이 아니라 로컬에서, 재현 가능하게" 계산하라고 요구하므로
// 그 산술과 상태 기계는 기기 없이 단위 테스트로 검증할 수 있는 곳에 둔다.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
