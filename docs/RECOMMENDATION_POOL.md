# 추천 풀 운영 정책 (O-1, O-5)

- **작성일**: 2026-09-15
- **관련 문서**: REQUIREMENTS.md v0.3 §5 (F-4 쇼츠 추천), FIRESTORE_SCHEMA.md §3.8
- **역할**: 서버 배치가 추천 풀을 채우고 유지하는 규칙을 확정한다.

---

## 1. O-1. 채널 화이트리스트

### 1.1 채널 ID를 이 문서에 적지 않는 이유

> REQUIREMENTS §5.1은 **"LLM에 영상 추천을 맡기지 않는다. 존재하지 않는 영상 ID를 생성한다"**를
> 설계 결정으로 못박았다. **채널 ID에도 같은 규칙이 적용된다.**
> 기억에 의존해 `UC...` 문자열을 적는 것은 §5.1이 금지한 바로 그 행위다.

이 문서는 **선정 기준과 후보 이름**만 확정하고, 실제 `channelId`와 `uploadsPlaylistId`는
아래 §1.4 절차로 **API에서 해석**한다. 해석 결과는 코드가 아니라 `meta/channels/state/{channelId}`에
저장되므로, 문서가 오래되어도 시스템은 틀리지 않는다.

### 1.2 선정 기준 (확정)

후보 채널은 아래를 **전부** 만족해야 화이트리스트에 넣는다.

| # | 기준 | 근거 |
|---|---|---|
| C-1 | 60초 이하 쇼츠를 **정기적으로** 업로드 | 배치가 매일 돌아도 신규가 없으면 풀이 마른다 |
| C-2 | 교육 목적 자체 제작 콘텐츠 | 영화·드라마 클립 재편집 채널은 저작권 리스크가 채널째로 옮겨온다 |
| C-3 | 발화가 명확하고 배경음악이 작음 | `MUSIC_HEAVY` 경고(전사 스키마 §3.4)가 대량 발생하면 풀 품질이 무너진다 |
| C-4 | 학습자 대상(ESL) — 원어민 대상 vlog 제외 | 원어민 쇼츠는 150~180 WPM (§8) |
| C-5 | 영어 단일 언어 | `language != "en"`이면 V-9로 등록 거부된다 |
| C-6 | 자막이 있으면 가점 (필수 아님) | 전사 검증 시 대조군으로 쓸 수 있다 |

### 1.3 후보 채널 (미검증 — 운영자 확인 필요)

아래는 C-1~C-5를 만족할 **가능성이 높은 후보 이름**이다. 핸들·ID·현재 업로드 상황은
**확인하지 않았다** (작업 환경에서 youtube.com 접근이 차단됨). 운영자가 §1.4로 확인한 뒤
통과한 것만 화이트리스트에 넣는다.

| 후보 | 예상 성격 | 비고 |
|---|---|---|
| BBC Learning English | 레벨 구분된 짧은 표현 강의 | A1~A2 대응 가능성 높음 |
| Learn English with Bob the Canadian | 느리고 또렷한 발화 | 왕초보 적합도 높을 것으로 예상 |
| Shaw English Online | 기초 표현 반복 | DRILL 유형이 많을 것으로 예상 |
| EnglishClass101 | 기초 단문 중심 | 업로드 빈도 확인 필요 |
| Speak English With Vanessa | 일상 표현 | 속도 확인 필요 |
| Rachel's English | 발음 중심 | 쉐도잉과 상성이 좋음 |
| Oxford Online English | 구조화된 강의 | B1 이상 비중 확인 필요 |
| mmmEnglish | 발음·문형 | 속도 확인 필요 |
| Accent's Way (Hadar) | 발음 중심 | 속도 확인 필요 |
| Easy English | 거리 인터뷰 | **C-4 위반 가능성** — 원어민 자연 발화 속도 |

> **중요**: 이 목록의 정확도가 낮아도 시스템은 안전하다. §5.2 4단계의
> **A1/A2 + WPM ≤ 120 게이트**가 최종 안전망이기 때문이다. 부적합한 채널을 넣으면
> 나쁜 영상이 추천되는 게 아니라 **그 채널에서 풀에 들어가는 영상이 0개가 된다.**
> 따라서 O-1은 "틀리면 망가지는 결정"이 아니라 "넓게 넣고 게이트로 거르는 결정"이다.
> 10~20개(§5.3)를 채우되, 확신이 없는 채널도 일단 넣고 30일 뒤 §1.5 지표로 정리한다.

### 1.4 채널 ID 해석 절차 (운영자 1회 실행)

```bash
# 1) 핸들 → 채널 ID + 업로드 재생목록 ID  (channels.list = 1 unit)
curl -s "https://www.googleapis.com/youtube/v3/channels\
?part=snippet,contentDetails\
&forHandle=@<handle>\
&key=$YT_API_KEY" \
| jq '{
    channelId: .items[0].id,
    title:     .items[0].snippet.title,
    uploads:   .items[0].contentDetails.relatedPlaylists.uploads
  }'

# 2) 최근 업로드 표본으로 C-1/C-3/C-4를 눈으로 확인  (playlistItems.list = 1 unit)
curl -s "https://www.googleapis.com/youtube/v3/playlistItems\
?part=contentDetails\
&playlistId=<uploads>\
&maxResults=50\
&key=$YT_API_KEY" | jq '.items | length'
```

- `channels.list`는 `id` 파라미터로 **최대 50개를 한 번에** 조회할 수 있다 (여전히 1 unit).
  핸들은 개별 조회가 필요하므로, 최초 1회만 채널당 1 unit을 쓰고 결과를 영구 저장한다.
- `uploadsPlaylistId`는 채널마다 불변이다. 한 번 저장하면 다시 조회할 필요가 없다.
- **`search.list`는 어떤 경우에도 쓰지 않는다** (100 units — §5.1).

### 1.5 화이트리스트 정리 기준 (30일 후)

채널별로 배치가 다음을 집계한다 (`meta/channels/state/{channelId}`).

| 지표 | 정리 기준 |
|---|---|
| `passRate` = 풀 등록 수 ÷ 분류 시도 수 | 30일간 **10% 미만이면 제외** — 분류 비용만 쓰고 결과가 없다 |
| `uploadsPer30d` | **0이면 휴면 처리** (분류 대상에서 제외, 삭제는 안 함) |
| `musicHeavyRate` | 50% 초과 시 제외 (C-3 위반) |

---

## 2. O-5. 갱신 주기 및 보관 기간

### 2.1 확정값

| 항목 | 값 | 근거 |
|---|---|---|
| 배치 주기 | **1일 1회, 03:00 KST** (`0 18 * * *` UTC) | §5.2. 사용자가 자는 시간에 돌려 Gemini 예산 경합을 피한다 |
| 수집 방식 | **증분** — 채널별 `lastPublishedAt` 커서 이후만 | 매일 전량 재분류하면 Gemini 비용이 선형으로 늘어난다 |
| 신규 분류 상한 | **1일 20편** | 아래 §2.2 |
| 풀 목표 크기 | **150~300편** (하한 100) | 아래 §2.3 |
| 보관 기간 | **`classifiedAt + 90일`** → Firestore TTL 자동 삭제 | 아래 §2.4 |
| 생존 확인 | **주 1회** `videos.list`로 풀 전체 대조 | 삭제·비공개 전환 영상 제거 |

### 2.2 분류 상한이 YouTube 쿼터가 아니라 Gemini 예산에서 나오는 이유

쿼터는 문제가 아니다. 채널 20개 기준 하루 소모량은 다음과 같다.

| 호출 | 횟수 | 단가 | 소계 |
|---|---|---|---|
| `playlistItems.list` (채널당 1페이지) | 20 | 1 | 20 |
| `videos.list` (신규 후보 ≤100편, 50개 배치) | 2 | 1 | 2 |
| 주간 생존 확인 (300편 ÷ 50) ÷ 7일 | ~1 | 1 | ~1 |
| **합계** | | | **≈ 23 units / 일** |

1일 한도 10,000 units 대비 **0.23%**. §5.1이 우려한 고갈은 `playlistItems` 방식에서는
발생하지 않는다.

**실제 병목은 Gemini다.** §F-1 제약에 따르면 무료 등급은 **1일 8시간 분량**의 유튜브 영상
처리가 상한이고, 이 예산은 배치의 난이도 분류와 **사용자 본인의 F-1 전사 요청이 공유**한다.

```
20편 × 60초 = 20분/일  →  8시간 예산의 4.2%
```

분류 상한을 20편으로 두면 사용자가 하루에 쇼츠 수십 개를 등록해도 예산이 겹치지 않는다.
상한 없이 돌리면 신규 채널을 추가한 날 수백 편이 한 번에 들어와 **그날 사용자 본인의
전사가 막힌다.** 이것이 상한의 진짜 목적이다.

### 2.3 풀 크기 유지

```kotlin
// 배치 시작 시
val poolSize = countPool(expiresAt > now)
val dailyCap = when {
    poolSize < 100 -> 40      // 하한 미달 — 일시적으로 상향
    poolSize < 300 -> 20      // 정상
    else           -> 0       // 충분 — 이번 회차는 수집만 하고 분류는 건너뜀
}
```

- 상한을 40으로 올려도 Gemini 예산의 8.3%라 안전하다.
- 풀이 300편을 넘으면 분류를 멈춘다. 1인 사용자가 하루 1~2편을 쓰므로 300편은 이미
  **5개월치 재고**다. 더 모으는 것은 비용만 든다.

### 2.4 보관 90일과 TTL

```bash
# expiresAt 필드에 TTL 정책 적용 (1회 설정)
gcloud firestore fields ttls update expiresAt \
  --collection-group=recommendationPool \
  --enable-ttl
```

- **90일인 이유**: 그보다 짧으면 풀이 목표 크기를 유지하지 못해 분류를 계속 돌려야 하고
  (= Gemini 비용), 그보다 길면 삭제·비공개된 영상이 풀에 남는 비율이 올라간다.
  1일 20편 × 90일 = 1,800편 유입 가능량이 목표 상한 300편을 크게 웃돌므로
  **보관 기간이 풀 크기의 제약이 되지 않는다** — 즉 §2.3의 상한 로직이 실제 조절기가 된다.
- **`expiresAt`을 재노출 시 갱신하지 않는다.** 갱신하면 한 번 추천된 영상이 영원히 남아
  풀이 고이게 된다. 만료는 오직 `classifiedAt` 기준이다.
- **사용자가 학습한 영상은 풀에서 지우지 않는다.** `users/{uid}/recommendationState`로
  기기에서 거른다 (FIRESTORE_SCHEMA §3.6). 공유 풀과 개인 상태의 경계를 지킨다.

### 2.5 실패 처리

| 상황 | 동작 |
|---|---|
| YouTube API 오류 | 해당 채널만 건너뛰고 커서를 **전진시키지 않는다**. 다음 회차에 재시도 |
| Gemini 분류 실패 | 해당 영상을 `meta/classifyFailures`에 기록, **최대 2회 재시도 후 영구 제외** |
| 쿼터 초과 (`quotaExceeded`) | 배치 즉시 중단. 커서 유지. 다음날 자동 회복 |
| 풀이 하한(100) 미달인 채로 3일 연속 | 운영자 알림 (예산 알림과 동일 경로) — 화이트리스트 고갈 신호 |

> 커서를 전진시키지 않는 것이 핵심이다. 실패한 회차에서 커서를 밀면 그 구간의 영상은
> **영원히 수집되지 않는다.** 증분 수집의 전형적인 사고다.

---

## 3. 배치 의사코드

```
for channel in whitelist where not dormant:
    state = meta/channels/state/{channel.id}
    items = playlistItems.list(channel.uploadsPlaylistId, maxResults=50)
    new   = items.filter { publishedAt > state.lastPublishedAt }
    if new.isEmpty(): continue

    details = videos.list(new.ids, part="contentDetails,snippet")   # 50개씩
    shorts  = details.filter { durationSec <= 60 }                  # §5.2 2단계
    fresh   = shorts.filter { not exists recommendationPool/{id} }
    candidates += fresh

candidates = candidates.sortedByDescending { publishedAt }.take(dailyCap)

for video in candidates:
    r = gemini.classify(video.url)        # {cefr, wpm, sentenceCount, topic}
    if r.cefr in [A1, A2] and r.wpm <= 120:          # §5.2 4단계
        recommendationPool/{video.id}.set(
            ..., classifiedAt = now, expiresAt = now + 90d)
    channelStats[video.channelId].attempted += 1

# 성공한 채널만 커서 전진 (§2.5)
for channel in succeededChannels:
    state.lastPublishedAt = max(new.publishedAt)
```

> `wpm`은 Gemini에게 묻지 않고 전사 결과의 단어 수 ÷ 발화 시간으로 계산한다
> (TRANSCRIPTION_SCHEMA §1.2 — `wpm`을 LLM에 물으면 매번 다른 값이 나온다).
> 위 의사코드의 `gemini.classify`는 분류 필드만 반환하고, `wpm`은 배치가 로컬 계산한다.

---

## 4. 남은 운영자 작업

| # | 작업 | 필요 시점 |
|---|---|---|
| 1 | §1.3 후보를 §1.4 절차로 확인하고 10~20개 확정 | 배치 최초 구동 전 |
| 2 | `gcloud firestore fields ttls update` 1회 실행 | 배치 최초 구동 전 |
| 3 | Google Cloud 예산 알림 설정 (§9.2) | 배치 최초 구동 전 |
| 4 | 30일 후 §1.5 지표로 화이트리스트 정리 | 운영 30일차 |
