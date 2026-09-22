# myna

**유튜브 영어 쉐도잉 쇼츠를 기본 소재로, 내 발음과 학습 횟수를 카운트하는 앱.**
Android (Kotlin / Jetpack Compose), 개인용 내부 테스트 트랙 배포.

현재 단계: **S0 구현 중** (REQUIREMENTS §11.1).

## 모듈

| 모듈 | 내용 | 빌드 |
|---|---|---|
| `core` | 학습 루프의 계산과 상태 기계. 순수 Kotlin/JVM, Android 의존 없음 | `./gradlew :core:test` — SDK 불필요 |
| `app` | Compose UI, 녹음(MediaRecorder), 재생(ExoPlayer) | Android SDK 필요 |

`core`에 계산을 몰아 둔 이유는 §9.4다 — 산술은 재현 가능해야 하므로 기기 없이 단위
테스트로 검증되는 자리에 둔다. 현재 테스트 63개.

`settings.gradle.kts`가 Android SDK 부재를 감지하면 `:app`을 제외하므로, SDK 없는
환경에서도 `:core` 테스트는 그대로 돌아간다.

> ⚠ `gradle/libs.versions.toml`의 **Android 의존 버전은 미검증이다.** 작성 환경에서
> Google Maven이 차단되어 최신 안정판을 조회하지 못했다. Android Studio가 제안하는
> 버전으로 맞추면 된다. `:core`는 이 값들과 무관하다.

## 문서

| 문서 | 내용 |
|---|---|
| [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) | 요구사항 명세 (SRS v0.3) — 범위, 기능, 아키텍처, 미결정 사항 |
| [docs/TRANSCRIPTION_SCHEMA.md](docs/TRANSCRIPTION_SCHEMA.md) | 전사 응답 JSON 스키마 (O-3) — 프록시 ↔ 앱 응답 계약, 검증 규칙 |
| [docs/FIRESTORE_SCHEMA.md](docs/FIRESTORE_SCHEMA.md) | Firestore 스키마 및 보안 규칙 (O-6) — 컬렉션 배치, 충돌 병합 전략, 비용 |
| [docs/RECOMMENDATION_POOL.md](docs/RECOMMENDATION_POOL.md) | 추천 풀 운영 정책 (O-1, O-5) — 채널 선정 기준, 배치 주기, 보관 기간 |

## 설치 (APK)

[Releases](../../releases)에서 APK를 내려받아 기기에 설치합니다.
새 릴리즈는 태그를 밀거나 Actions 탭의 `release` 워크플로를 수동 실행하면 만들어집니다.

```bash
git tag v0.1.0 && git push origin v0.1.0
```

APK는 레포의 **고정 디버그 키스토어**(`app/debug.keystore`)로 서명됩니다. 비밀번호가
공개된 표준 안드로이드 디버그 자격증명이라 비밀이 아니며, 사이드로드 설치 전용입니다.
고정해 두는 이유는 서명이 빌드마다 바뀌면 덮어쓰기 설치가 막혀
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) 앱을 지웠다 깔아야 하고, 그때 학습 기록이
함께 사라지기 때문입니다.

> Play Store 업로드에는 쓸 수 없습니다. 스토어 배포는 S1에서 별도 업로드 키로 진행합니다.

## 설정 파일

| 파일 | 배포 |
|---|---|
| `firestore.rules` | `firebase deploy --only firestore:rules` |
| `firestore.indexes.json` | `firebase deploy --only firestore:indexes` |

`recommendationPool`의 TTL 정책은 인덱스 파일로 설정되지 않는다. 1회 수동 실행이 필요하다:

```bash
gcloud firestore fields ttls update expiresAt \
  --collection-group=recommendationPool --enable-ttl
```

## S0 구현 범위

F-2(업로드) + L1 + F-3(예산 세션) + F-7(3단계 모드). 외부 API 0개.

| 기능 | 위치 |
|---|---|
| 반복 횟수 산출 (§4.2, §4.4) | `core/session/RepsCalculator.kt` |
| 예산 적응 (§4.7) | `core/budget/BudgetAdaptation.kt` |
| 3단계 모드 · 카운트 (§7.2, §4.3) | `core/session/SessionEngine.kt` |
| 전사 검증 V-1~V-9 | `core/transcript/TranscriptValidator.kt` |
| 녹음 보관 (§4.5) | `core/recording/RecordingRetention.kt` |
| 날짜 판정 · 스트릭 (§4.6) | `core/daily/DailySpeechLog.kt` |
| 수동 문장 입력 (스키마 §4.3) | `app/ui/entry/ManualEntryScreen.kt` |
| 세션 화면 | `app/ui/session/` |

S0에는 전사 API가 없으므로 **수동 문장 입력이 유일한 입력 경로**다. 이 화면은 어차피
스키마 §4.3이 "선택이 아니라 필수"로 규정한 것이라 S1 이후에도 추출 실패 영상을 받는다.

## 다음 단계

S0는 리허설이고 **최소 출시선은 S1**(프록시 + 유튜브 경로)이다. 제품 정의가
유튜브 기본이기 때문이다.
