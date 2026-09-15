// Kotlin 플러그인은 여기서 한 번만 버전을 정한다.
// 서브프로젝트가 각자 버전을 붙이면 "Kotlin Gradle plugin was loaded multiple times" 경고가 난다.
//
// AGP(com.android.application)는 여기 올리지 않는다. `apply false`로 선언해도 Gradle이
// 플러그인을 해석하려 들기 때문에, Google Maven에 닿지 못하는 환경에서는 :core 빌드까지
// 함께 실패한다. AGP는 :app만 쓰므로 :app에 남겨 둔다.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}
