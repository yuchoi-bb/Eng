# Eng — 영어 쉐도잉 앱

**유튜브 영어 쉐도잉 쇼츠를 기본 소재로, 내 발음과 학습 횟수를 카운트하는 앱.**
Android (Kotlin / Jetpack Compose), 개인용 내부 테스트 트랙 배포.

현재 단계: **설계 문서만 존재한다. 구현 코드 없음.**

## 문서

| 문서 | 내용 |
|---|---|
| [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) | 요구사항 명세 (SRS v0.3) — 범위, 기능, 아키텍처, 미결정 사항 |
| [docs/TRANSCRIPTION_SCHEMA.md](docs/TRANSCRIPTION_SCHEMA.md) | 전사 응답 JSON 스키마 (O-3) — 프록시 ↔ 앱 응답 계약, 검증 규칙 |
| [docs/FIRESTORE_SCHEMA.md](docs/FIRESTORE_SCHEMA.md) | Firestore 스키마 및 보안 규칙 (O-6) — 컬렉션 배치, 충돌 병합 전략, 비용 |
| [docs/RECOMMENDATION_POOL.md](docs/RECOMMENDATION_POOL.md) | 추천 풀 운영 정책 (O-1, O-5) — 채널 선정 기준, 배치 주기, 보관 기간 |

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

## 다음 단계

REQUIREMENTS §11.1의 **S0** — F-2(업로드) + L1 + F-3(예산 세션) + F-7(3단계 모드).
외부 API 0개로 반복·카운트 루프를 먼저 검증한다.

단 S0는 리허설이고 **최소 출시선은 S1**(프록시 + 유튜브 경로)이다. 제품 정의가
유튜브 기본이기 때문이다.
