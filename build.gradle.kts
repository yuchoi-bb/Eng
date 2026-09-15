// 루트는 비워 둔다.
//
// 흔한 관례는 여기서 플러그인을 `apply false`로 선언해 버전을 한곳에 모으는 것이지만,
// 이 프로젝트에서는 두 가지가 그것을 막는다.
//
// 1. AGP를 여기 올리면, Google Maven에 닿지 못하는 환경에서 Gradle이 AGP를 해석하려다
//    :core 빌드까지 함께 죽는다. :core는 Android 없이 돌아가는 것이 존재 이유다.
// 2. AGP 없이 Kotlin 플러그인만 올리면 더 나쁘다. kotlin-android가 루트 클래스로더에서
//    적용되는데 그 클래스로더에는 AGP가 없어, KotlinAndroidTarget이
//    com/android/build/gradle/api/BaseVariant를 찾지 못하고 설정 단계에서 실패한다.
//
// 그래서 플러그인은 각 모듈이 직접 선언한다. 대가로 Kotlin 플러그인이
// ":app과 :core에서 각각 로드됐다"는 경고가 뜨지만, 두 모듈이 같은 버전을 쓰므로
// 실질적인 문제가 되지 않는다. 경고를 없애려고 위 두 구성 중 하나로 바꾸면 빌드가 깨진다.
