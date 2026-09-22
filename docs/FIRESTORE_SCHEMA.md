# Firestore 스키마 및 보안 규칙 (O-6)

- **버전**: schemaVersion 1
- **작성일**: 2026-09-15
- **관련 문서**: REQUIREMENTS.md v0.3 (§9.1.1 동기화 범위), TRANSCRIPTION_SCHEMA.md (schemaVersion 1)
- **역할**: 전사 스키마를 영속 저장소 구조로 확정한다. 앱의 유일한 동기화 저장소.

---

## 1. 설계 결정

### 1.1 컬렉션 배치

```
users/{uid}
  ├─ (문서 필드)  settings, schemaVersion, createdAt, updatedAt
  ├─ dailyLogs/{date}                    date = "2026-09-15" (ISO-8601)
  ├─ videoPlans/{videoPlanId}
  │    └─ sentences/{paddedIndex}        "000", "001", ...
  ├─ sentenceProgress/{sentenceId}
  ├─ reviewQueue/{sentenceId}
  ├─ recommendationState/{videoId}
  └─ expressions/{lemmaKey}              F-8 (v2) — v1에서도 적재만

recommendationPool/{videoId}             배치 전용 쓰기 / 앱 읽기 전용
meta/channels/state/{channelId}          배치 커서 (앱 접근 없음)
```

단일 사용자 앱이지만 **모든 학습 데이터를 `users/{uid}` 아래에 둔다.** 보안 규칙이
`request.auth.uid == uid` 한 줄로 끝나고, 규칙 파일에 이메일 같은 개인정보를 박아 넣지 않아도 된다.

### 1.2 문서 ID를 키로 쓴다

| 컬렉션 | 문서 ID | 이유 |
|---|---|---|
| `dailyLogs` | `yyyy-MM-dd` | REQUIREMENTS §4.6 — 자정 롤오버 작업 없이 **조회 시점에 `LocalDate` 키로 판정**. ISO 형식이라 문서 ID의 사전순 = 시간순 → F-5 스트릭을 `orderBy(documentId(), DESC).limit(30)` 한 번으로 계산 |
| `videoPlans` | `YT_<videoId>` / `UP_<uuid>` / `NT_<noteId>` | TRANSCRIPTION_SCHEMA §3.1 — `sourceRef` 중복 등록 방지. 결정적 ID라 별도 중복 검사 쿼리가 필요 없다 (`set(merge)` 재요청 = 캐시 갱신). `NT_`는 현장 메모에서 만든 문장 연습이다 |
| `sentences` | 3자리 제로패딩 `000` | Firestore는 문서 ID를 **사전순**으로 정렬한다. 패딩 없이 `"10" < "2"`가 되어 문장 순서가 깨진다 |
| `sentenceProgress` / `reviewQueue` | `<videoPlanId>_s<index>` | 두 컬렉션이 같은 문장을 가리키므로 ID를 공유한다 (조인 불필요) |
| `expressions` | lemma 정규화 (소문자, 공백→`_`) | `be up to` → `be_up_to`. 누적 카운트가 자연스럽게 병합됨 |

### 1.3 문장은 서브컬렉션

TRANSCRIPTION_SCHEMA §6의 권고를 따른다. 근거는 두 가지다.

1. **문서 1MB 한도** — `translationKo` + `transliterationKo` + `breathGroups`까지 포함한 문장이
   수십 개면 단일 문서로는 위험하다.
2. **쓰기 분리** — 문장 *내용*은 검증 후 불변이고, 문장 *진행도*는 세션 중 계속 갱신된다.
   같은 문서에 두면 카운트 1회마다 전사 결과 전체를 재전송하게 된다.

---

## 2. 충돌 병합 전략 (태블릿 3대)

> REQUIREMENTS §9.1은 "단일 사용자라 기본 Last-Write-Wins로 충분"이라고 적었다.
> **누적 카운터에는 그대로 적용하면 안 된다.** 태블릿 A와 B가 각각 오프라인에서
> `achievedSec`를 200 → 260, 200 → 240으로 쓰면 LWW는 한쪽을 통째로 버린다.
> 실제로는 100초를 말했는데 40초 또는 60초만 기록된다.

필드별로 병합 방식을 고정한다.

| 필드 종류 | 예시 | 병합 방식 |
|---|---|---|
| 누적 수치 | `achievedSec`, `countedUtterances`, `totalCounts`, `occurrenceCount` | **`FieldValue.increment()`** — 서버에서 교환법칙이 성립, 오프라인 큐에서도 합산 보존 |
| 집합 | `videoIds` | **`FieldValue.arrayUnion()`** |
| 설정·상태 스칼라 | `dailyTargetSec`, `targetReps`, `playbackRate`, `clearedStage` | LWW (기본값) |
| 불변 내용 | `sentences/*`, 전사 결과 | 최초 1회 쓰기 후 수정 없음 (수동 교정 경로 제외) |
| 단조 증가 스칼라 | `stage`, `bestScore` | 클라이언트에서 `max(local, remote)` 적용 후 쓰기 |

**쓰기 빈도**: REQUIREMENTS §4.5는 녹음 종료마다 자동 +1이다. 단계마다 Firestore에 쓰면
38회 반복 세션이 100회 이상의 쓰기가 된다. **§7.2의 "3단계 완주 = 1카운트" 시점에만 플러시**하고,
그 사이는 메모리에 버퍼링한다. 오프라인 퍼시스턴스가 있으므로 네트워크 유무와 무관하게 동작한다.

---

## 3. 문서 정의

### 3.1 `users/{uid}`

```jsonc
{
  "schemaVersion": 1,
  "settings": {
    "dailyTargetSec": 300,          // §8 왕초보 기본값
    "defaultAutoReps": true,
    "defaultManualReps": 30,
    "playbackRate": 0.75,           // §8
    "showTransliterationKo": false, // §8 한글 음차 표기 옵션
    "onboardedAt": "<timestamp>"
  },
  "createdAt": "<serverTimestamp>",
  "updatedAt": "<serverTimestamp>"
}
```

### 3.2 `users/{uid}/dailyLogs/{date}`

```jsonc
{
  "schemaVersion": 1,
  "date": "2026-09-15",
  "dailyTargetSec": 300,        // ★ 그날 적용된 목표를 스냅샷
  "achievedSec": 412,           // increment
  "reviewSec": 60,              // increment — achievedSec에 포함된 값 중 복습 분 (§7.1)
  "countedUtterances": 18,      // increment
  "videoIds": ["YT_dQw4w9WgXcQ"], // arrayUnion
  "updatedAt": "<serverTimestamp>"
}
```

**`dailyTargetSec`를 로그에 복사하는 이유**: §4.7 적응 로직이 예산을 매일 ±10~20% 바꾼다.
목표값을 `settings`에서만 읽으면, 예산이 바뀐 순간 **과거 날짜의 완료율이 소급해서 틀려진다.**
적응 로직 자신이 그 완료율을 입력으로 쓰므로 되먹임이 오염된다. 그날의 분모는 그날 문서에 고정한다.

`completionRate`는 저장하지 않는다. `achievedSec / dailyTargetSec`로 조회 시 계산한다
(§9.4 — 산술은 로컬에서).

### 3.3 `users/{uid}/videoPlans/{videoPlanId}`

전사 응답(TRANSCRIPTION_SCHEMA §2)의 최상위 필드를 그대로 받고, 로컬 산술 결과를 캐시한다.

```jsonc
{
  "schemaVersion": 1,
  // --- 전사 응답에서 그대로 (LLM 영역) ---
  "source": "YOUTUBE",           // YOUTUBE | UPLOAD | NOTE
  "sourceRef": "dQw4w9WgXcQ",
  "language": "en",
  "type": "DIALOGUE",            // DRILL | SINGLE | DIALOGUE
  "cefr": "A2",
  "timestampUnit": "SECOND",     // SECOND면 재생 시 ±300ms 패딩 (§1.1)
  "speechStartMs": 1200,
  "speechEndMs": 41800,
  "warnings": ["MUSIC_HEAVY"],

  // --- 앱이 로컬 계산해 캐시 (§9.4) ---
  "speechSec": 40,               // (speechEndMs - speechStartMs) / 1000
  "wpm": 105,                    // 단어 수 ÷ 발화 분
  "sentenceCount": 6,
  "suggestedReps": 8,            // ceil(dailyTargetSec / speechSec).coerceIn(5, 50)

  // --- 사용자 설정 / 진행 상태 ---
  "targetReps": 8,
  "autoReps": true,
  "completedReps": 3,            // increment
  "lastSessionAt": "<timestamp>",

  "transcribedAt": "<timestamp>",
  "createdAt": "<serverTimestamp>",
  "updatedAt": "<serverTimestamp>"
}
```

> **업로드 영상의 재생 URI는 이 문서에 넣지 않는다.**
> §9.1.1에서 원본 영상은 동기화 대상이 아니다. 게다가 Photo Picker가 준 `content://` URI와
> persistable 권한은 **그 기기의 그 설치본**에만 유효하다 — 재설치만 해도 끊긴다.
> 따라서 `videoPlanId → 로컬 URI` 매핑은 **기기 로컬 DataStore**에 둔다.
> 태블릿 B에서 UPLOAD 계획을 열면 문장·번역은 보이지만 재생이 불가하므로,
> UI는 "이 기기에 원본 없음"을 명시하고 세션 시작을 막아야 한다.

#### `users/{uid}/videoPlans/{videoPlanId}/sentences/{paddedIndex}`

TRANSCRIPTION_SCHEMA §3.2를 그대로 옮긴다. **검증(§4.2 V-1~V-9)을 통과한 문장만 쓴다.**

```jsonc
{
  "index": 0,
  "text": "What are you up to this weekend?",
  "translationKo": "이번 주말에 뭐 해?",
  "transliterationKo": "왓 아 유 업 투 디스 위켄드",
  "startMs": 1200,
  "endMs": 3400,
  "keywords": ["up to", "weekend"],
  "breathGroups": [               // type=SINGLE일 때만 필수
    { "text": "What are you up to", "startMs": 1200, "endMs": 2500 }
  ],
  "edited": false,                // 수동 교정 경로(§4.3)로 수정되면 true
  "editedAt": null
}
```

### 3.4 `users/{uid}/sentenceProgress/{sentenceId}`

```jsonc
{
  "sentenceId": "YT_dQw4w9WgXcQ_s0",
  "videoPlanId": "YT_dQw4w9WgXcQ",
  "sentenceIndex": 0,
  "clearedStage": 2,              // 0~3, §7.2 쉐도잉 3단계
  "totalCounts": 14,              // increment — 3단계 완주 횟수

  // L2 — 온디바이스, 매 발화 동기 판정
  "lastScore": 67,                // 키워드 부분 점수
  "bestScore": 100,               // max() 병합

  // L3 — Azure, 비동기 도착 (REQUIREMENTS §6.3)
  "lastPronScore": 72,            // 종합 발음 점수
  "lastPronDetail": {             // 화면 표시용 세부 점수
    "accuracy": 78, "fluency": 65, "completeness": 100, "prosody": 61
  },
  "bestPronScore": 81,            // max() 병합 — F-9 성장 지표
  "pronScoredAt": "<timestamp>",
  "firstRecordingPath": "users/{uid}/firstRecordings/YT_dQw4w9WgXcQ_s0.m4a",
  "firstRecordingAt": "<timestamp>",
  "lastPracticedAt": "<timestamp>"
}
```

> REQUIREMENTS §9.3의 `firstRecordingUri`를 **Cloud Storage 객체 경로**로 확정한다.
> §9.1.1에서 동기화되는 유일한 녹음이 이것이기 때문이다. 로컬 `file://` 경로를 넣으면
> 다른 태블릿에서 F-9(성장 기록) 비교 재생이 깨진다.
> §4.5의 "최근 3회분" 순환 녹음은 **Firestore에 전혀 등장하지 않는다** (기기 로컬 전용).

> **L3 점수는 비동기로 도착한다.** 세션 중 L2가 먼저 `lastScore`를 쓰고, Azure 응답이
> 돌아오면 `lastPronScore`가 나중에 채워진다. 두 필드는 서로 다른 시점에 갱신되므로
> **한 번의 `set()`으로 함께 쓰지 않는다** — L3 응답이 늦게 도착해 그 사이 진행된
> L2 결과를 덮어쓰는 사고를 막기 위해, 각각 `update()`로 자기 필드만 건드린다.
> L3에 보낸 녹음 자체는 프록시를 통과만 하고 어디에도 저장하지 않는다.

### 3.5 `users/{uid}/reviewQueue/{sentenceId}` — F-6

```jsonc
{
  "sentenceId": "YT_dQw4w9WgXcQ_s0",
  "videoPlanId": "YT_dQw4w9WgXcQ",
  "sentenceIndex": 0,

  // 비정규화 (아래 근거)
  "text": "What are you up to this weekend?",
  "translationKo": "이번 주말에 뭐 해?",
  "keywords": ["up to", "weekend"],
  "startMs": 1200,
  "endMs": 3400,

  "stage": 1,                     // 0:1일 1:3일 2:7일 3:14일
  "nextDueDate": "2026-09-18",
  "failCount": 2,
  "lastResult": "FAIL",           // PASS | FAIL
  "updatedAt": "<serverTimestamp>"
}
```

**비정규화 근거**: 복습 세션은 "오늘 만기 N문장"을 한 번에 그려야 한다. 정규화하면
N개의 `sentences` 문서를 개별 조회해야 하고, 오프라인 캐시 미스 시 화면이 비어버린다.
문장 내용은 불변이므로 복사본이 어긋날 위험이 없다 (수동 교정 시에만 전파).

**간격 테이블** (§7.1):

| stage | 간격 | 통과 시 | 실패 시 |
|---|---|---|---|
| 0 | 1일 | → 1 | → 0 (리셋) |
| 1 | 3일 | → 2 | → 0 |
| 2 | 7일 | → 3 | → 0 |
| 3 | 14일 | **졸업 — 큐에서 삭제** | → 0 |

> stage 3 통과 후 처리는 SRS에 없어 **제안 기본값**으로 둔다: 큐에서 삭제하고
> `sentenceProgress`에만 이력을 남긴다. 큐를 무한히 키우지 않기 위함이다.

**쿼리**: `where("nextDueDate", "<=", today).orderBy("nextDueDate")` — 단일 필드
인덱스라 자동 생성된다. 복합 인덱스 불필요.

### 3.6 `users/{uid}/recommendationState/{videoId}` — F-4

추천 개인화 상태. **공유 풀(`recommendationPool`)에 절대 쓰지 않는다.**

```jsonc
{ "status": "DISMISSED", "seenAt": "<timestamp>", "startedAt": null }
// status: SEEN | DISMISSED | STARTED | COMPLETED
```

### 3.7 `users/{uid}/expressions/{lemmaKey}` — F-8 (v2)

TRANSCRIPTION_SCHEMA §3.3 — v1에서도 **응답에 포함되므로 적재만** 한다.

```jsonc
{
  "text": "be up to",
  "meaningKo": "~을 하다 (계획)",
  "occurrenceCount": 3,           // increment
  "videoIds": ["YT_a", "YT_b"],   // arrayUnion
  "firstSeenAt": "<timestamp>",
  "updatedAt": "<serverTimestamp>"
}
```

### 3.8 `recommendationPool/{videoId}` — 배치 전용

```jsonc
{
  "schemaVersion": 1,
  "videoId": "abc123",
  "channelId": "UC...",
  "channelTitle": "…",
  "title": "…",
  "durationSec": 48,              // ≤ 60 (§5.2)
  "cefr": "A2",                   // A1 | A2 만 적재 (§5.2)
  "wpm": 112,                     // ≤ 120 만 적재
  "sentenceCount": 5,
  "topic": "smalltalk",
  "publishedAt": "<timestamp>",
  "classifiedAt": "<timestamp>",
  "expiresAt": "<timestamp>"      // ★ Firestore TTL 정책 대상 필드
}
```

앱은 **읽기 전용**이다. 정렬·개인화는 `recommendationState`와 로컬 이력으로 기기에서 수행한다(§5.2).

---

## 4. 보안 규칙

`firestore.rules` 참조. 핵심 결정 세 가지.

1. **소유권은 경로로 판정한다** — `request.auth.uid == uid`. 규칙 파일에 이메일을 하드코딩하지
   않는다. 본인 이메일 화이트리스트(§9.2, O-4)는 **프록시 계층**의 책임이고, Firestore는
   "인증된 본인의 경로"만 보장하면 충분하다. 레포가 공개될 가능성이 있는 한 규칙 파일에
   개인정보를 넣는 것은 불필요한 노출이다.
2. **`recommendationPool`과 `meta`는 클라이언트 쓰기 전면 차단.** 배치는 Admin SDK로 쓰므로
   규칙을 우회한다 — 즉 `allow write: if false`가 배치를 막지 않는다.
3. **불변 필드를 규칙에서 막는다** — `sentences`의 전사 내용, `dailyLogs`의 `date`,
   `videoPlans`의 `source`/`sourceRef`. 클라이언트 버그로 전사 결과가 덮어써지는 사고를 차단한다.

`schemaVersion` 검증도 규칙에 넣는다. 구버전 앱이 신버전 문서를 망가뜨리는 것을 막는다.

---

## 5. 인덱스

`firestore.indexes.json` 참조. 단일 필드 인덱스는 Firestore가 자동 생성하므로
**복합 인덱스가 필요한 쿼리는 하나뿐이다.**

| 쿼리 | 인덱스 |
|---|---|
| 복습 만기: `nextDueDate <= today` | 자동 (단일 필드) |
| 스트릭: `orderBy(documentId(), DESC).limit(30)` | 자동 (`__name__`) |
| 최근 영상: `orderBy("lastSessionAt", DESC)` | 자동 |
| 주제별 추천: `where("topic", ==).orderBy("publishedAt", DESC)` | **복합 필요** |

---

## 6. 오프라인 퍼시스턴스

```kotlin
// Application.onCreate — 최초 Firestore 접근 전에 설정해야 한다
FirebaseFirestore.getInstance().firestoreSettings = firestoreSettings {
    setLocalCacheSettings(
        persistentCacheSettings {
            setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
        }
    )
}
// 오프라인 쿼리가 캐시 전체를 스캔하지 않도록 로컬 인덱스 자동 생성
FirebaseFirestore.getInstance().persistentCacheIndexManager?.enableIndexAutoCreation()
```

- 캐시 크기를 무제한으로 두는 이유: 기본 100MB에서 LRU 축출이 일어나면 **오프라인 상태의 복습 큐가
  비어 보인다.** 단일 사용자 데이터는 텍스트뿐이라 무제한이어도 실측 수십 MB를 넘지 않는다.
- 전사 요청은 Firestore 오프라인 쓰기로 대체할 수 없다(§9.1). WorkManager 큐를 유지한다.

---

## 7. 비용 점검 (Spark 무료 등급)

| 한도 | 일일 |
|---|---|
| 읽기 | 50,000 |
| 쓰기 | 20,000 |
| 삭제 | 20,000 |
| 저장 | 1 GiB |

§2의 "3단계 완주 시점에만 플러시" 규칙을 지키면, 38회 반복 세션의 쓰기는
`sentenceProgress` 38 + `dailyLogs` 38 + `videoPlans` 1 ≈ **80회**. 하루 여러 세션을 돌려도
무료 등급의 0.5% 수준이다. 녹음은 Firestore가 아닌 Cloud Storage(최초 1개만)로 가므로
1 GiB 저장 한도와도 무관하다.

---

## 8. 마이그레이션

모든 최상위 문서에 `schemaVersion`을 둔다. 앱은 기동 시 자신이 아는 버전보다 **높은** 문서를
발견하면 쓰기를 중단하고 업데이트를 안내한다. 낮은 문서는 읽기 시점에 승격한다
(lazy migration — 단일 사용자라 일괄 마이그레이션 잡이 필요 없다).
