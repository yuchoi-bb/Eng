# 전사 응답 JSON 스키마 (O-3)

- **버전**: schemaVersion 1
- **작성일**: 2026-09-15
- **관련 문서**: REQUIREMENTS.md v0.3
- **역할**: 프록시 ↔ 앱 사이의 응답 계약. Firestore 스키마(O-6)의 입력이 된다.

---

## 1. 설계 원칙

### 1.1 두 소스, 하나의 스키마

유튜브 경로와 업로드 경로는 **처리 방식이 다르지만 응답 형태는 동일**해야 한다. 앱은 `source` 필드 외에는 분기하지 않는다.

| | 유튜브 (F-1) | 업로드 (F-2) |
|---|---|---|
| 1단계 | — | `gemini-3.5-transcribe` → 단어 타임스탬프 |
| 2단계 | 멀티모달 모델에 URL 전달 → JSON | 1단계 결과를 텍스트로 전달 → JSON |
| 타임스탬프 정밀도 | **초 단위** | **밀리초 단위** |

> **정밀도 차이 주의**: 유튜브 경로는 문장 경계가 초 단위라 구간 반복 시 앞뒤가 잘릴 수 있다.
> → 앱에서 재생 시 **시작 −300ms / 종료 +300ms 패딩**을 적용한다. 스키마에는 원본 값을 그대로 저장한다.

### 1.2 LLM이 채우지 않는 필드

REQUIREMENTS 9.4절(LLM 사용 경계)에 따라 **산술 결과는 스키마에 포함하지 않는다.**

| 값 | 출처 |
|---|---|
| `speechSec`, `wpm`, `suggestedReps` | **앱에서 타임스탬프로 계산** |
| `cefr`, `type`, `keywords`, 번역 | LLM (판단 영역) |

`wpm`을 LLM에 물어보면 매번 다른 값이 나온다. 단어 수 ÷ 발화 시간으로 로컬 계산한다.

### 1.3 구조화 출력 강제

프롬프트로만 "JSON으로 답해"라고 하지 말고 **`responseSchema`(구조화 출력)를 지정**한다. 프롬프트 방식은 앞뒤에 설명 문구나 코드펜스가 섞여 파싱이 깨진다.

---

## 2. 스키마 정의

```jsonc
{
  "schemaVersion": 1,
  "source": "YOUTUBE",              // YOUTUBE | UPLOAD
  "sourceRef": "dQw4w9WgXcQ",       // 유튜브 videoId 또는 업로드 파일 UUID
  "language": "en",                 // 감지된 주 언어 (ISO 639-1)
  "type": "DIALOGUE",               // DRILL | SINGLE | DIALOGUE
  "cefr": "A2",                     // A1 | A2 | B1 | B2 | C1 | C2
  "timestampUnit": "SECOND",        // SECOND | MILLISECOND
  "speechStartMs": 1200,            // 첫 단어 시작
  "speechEndMs": 41800,             // 마지막 단어 종료
  "sentences": [
    {
      "index": 0,
      "text": "What are you up to this weekend?",
      "translationKo": "이번 주말에 뭐 해?",
      "transliterationKo": "왓 아 유 업 투 디스 위켄드",
      "startMs": 1200,
      "endMs": 3400,
      "keywords": ["up to", "weekend"],
      "breathGroups": [
        { "text": "What are you up to", "startMs": 1200, "endMs": 2500 },
        { "text": "this weekend?",      "startMs": 2500, "endMs": 3400 }
      ]
    }
  ],
  "expressions": [
    { "text": "be up to", "meaningKo": "~을 하다 (계획)" }
  ],
  "warnings": ["MUSIC_HEAVY"]
}
```

---

## 3. 필드별 명세

### 3.1 최상위

| 필드 | 타입 | 필수 | 사용처 | 비고 |
|---|---|---|---|---|
| `schemaVersion` | int | ✅ | 마이그레이션 | 현재 1 |
| `source` | enum | ✅ | F-1/F-2 분기 | |
| `sourceRef` | string | ✅ | 중복 등록 방지 | 동일 값 재요청 시 캐시 반환 |
| `language` | string | ✅ | 비영어 영상 차단 | `en` 아니면 등록 거부 |
| `type` | enum | ✅ | **4.4 반복 방식 결정** | DRILL은 reps × 0.5 |
| `cefr` | enum | ✅ | **5.2 추천 필터** | A1/A2만 추천 풀 등록 |
| `timestampUnit` | enum | ✅ | 재생 패딩 적용 여부 | SECOND면 ±300ms 패딩 |
| `speechStartMs` | int | ✅ | **4.2 횟수 자동 계산** | |
| `speechEndMs` | int | ✅ | 〃 | |
| `warnings` | string[] | | 사용자 경고 표시 | 아래 3.4 |

### 3.2 sentences[]

| 필드 | 타입 | 필수 | 사용처 |
|---|---|---|---|
| `index` | int | ✅ | 카운트 단위 (4.3절: 문장 1개 = 1카운트) |
| `text` | string | ✅ | 자막 표시, L2 채점 기준 |
| `translationKo` | string | ✅ | **왕초보 튜닝** — 한글 뜻 병기 |
| `transliterationKo` | string | | 한글 음차 표기 옵션 |
| `startMs` / `endMs` | int | ✅ | 구간 반복 재생 |
| `keywords` | string[] | ✅ | **L2 부분 점수 채점** |
| `breathGroups` | array | | `type=SINGLE`일 때만 필수 |

**`keywords` 규칙**
- 문장당 **2~4개**. 너무 많으면 왕초보 점수가 다시 낮아진다
- 관사·전치사 단독 금지. 의미 단위(내용어 또는 덩어리 표현)만
- 반드시 `text` 안에 실제로 등장하는 표현이어야 한다 (4.2절 검증 참조)

**`breathGroups` 규칙**
- `type=SINGLE`(긴 문장 1회 발화) 영상에서 부분 반복에 사용 (REQUIREMENTS 4.4절)
- 각 그룹은 **1.5~3초**를 목표로 분할
- 그룹 타임스탬프 합은 문장 범위를 벗어나지 않아야 한다

### 3.3 expressions[] — F-8 (v2)

v1에서도 **응답에는 포함시키되 저장만** 한다. 나중에 별도 재호출로 소급 추출하면 비용이 두 배가 된다.

| 필드 | 타입 | 비고 |
|---|---|---|
| `text` | string | 표제형(lemma). 예: `be up to` |
| `meaningKo` | string | 한글 뜻 |

### 3.4 warnings[] enum

| 값 | 의미 | 앱 동작 |
|---|---|---|
| `MUSIC_HEAVY` | 배경음악이 커서 전사 신뢰도 낮음 | 등록 시 경고 표시 |
| `MULTIPLE_SPEAKERS` | 화자 3인 이상 | 정보 표시만 |
| `FAST_SPEECH` | 발화 속도 과다 | 재생속도 0.5x 권장 안내 |
| `LOW_CONFIDENCE` | 전사 신뢰도 낮음 | 수동 수정 유도 |

---

## 4. 검증 규칙

### 4.1 원칙

> **LLM 출력을 신뢰하지 않는다.** 앱은 저장 전에 아래를 전부 검사하고, 실패 시 저장하지 않는다.

### 4.2 필수 검증 항목

| # | 규칙 | 실패 시 |
|---|---|---|
| V-1 | `sentences`가 비어 있지 않음 | 등록 거부 |
| V-2 | 각 문장의 `startMs < endMs` | 해당 문장 제외 |
| V-3 | `startMs`가 index 순으로 **단조 증가** | 정렬 후 재검사 |
| V-4 | 모든 타임스탬프가 영상 길이 이내 | 초과분 클램프 |
| V-5 | `keywords`의 각 항목이 `text`에 실제 포함 (대소문자·구두점 무시) | 미포함 항목 제거 |
| V-6 | `keywords` 제거 후 0개가 되면 → 내용어 상위 2개로 **로컬 대체** | |
| V-7 | `cefr`, `type`, `warnings`가 정의된 enum 값 | 미지정 값은 각각 `B1`, `DIALOGUE`, 무시로 폴백 |
| V-8 | `type=SINGLE`인데 `breathGroups` 없음 | `type=DIALOGUE`로 강등 |
| V-9 | `language != "en"` | 등록 거부, 사용자 안내 |

### 4.3 실패 처리

1. JSON 파싱 실패 → **1회 재요청** (동일 입력, temperature 낮춤)
2. 재요청도 실패 → 사용자에게 "스크립트 추출 실패" 안내 + **수동 문장 입력** 화면 제공
3. 전사 자체는 성공했으나 검증에서 문장이 모두 탈락 → 2번과 동일 처리

> 수동 입력 경로는 선택이 아니라 **필수**다. 이게 없으면 추출 실패 영상은 학습에 쓸 수 없다.

---

## 5. 프롬프트 요건 (요약)

구조화 출력을 쓰므로 프롬프트는 짧게 유지한다. 반드시 포함할 지시:

1. 학습자가 **영어 왕초보(A1~A2)**임을 명시 → `translationKo`를 직역체가 아닌 자연스러운 구어체로
2. `type` 분류 기준 3종의 정의를 명시 (DRILL / SINGLE / DIALOGUE)
3. `keywords`는 **문장에 실제 등장하는 표현만** (환각 방지)
4. 숫자 계산·요약·조언을 하지 말 것 (스키마 필드만 채움)
5. 노래 가사, 음악이 주된 영상은 `MUSIC_HEAVY` 경고를 붙일 것

---

## 6. 후속 영향

| 문서 | 영향 |
|---|---|
| REQUIREMENTS O-6 | Firestore 문서 구조를 이 스키마에 맞춰 설계 (sentences는 서브컬렉션 권장 — 문장 진행도가 문장별로 갱신되므로) |
| REQUIREMENTS 4.2 | `speechSec = speechEndMs - speechStartMs`로 확정 |
| REQUIREMENTS 6 (L2) | 채점 기준은 `keywords` 배열 |
| REQUIREMENTS 7.2 (F-7) | 3단계 모드의 자막 표시는 `text` / `translationKo` 조합 |
