package com.myna.core.model

import kotlinx.serialization.Serializable
/**
 * 한 영상의 학습 계획. REQUIREMENTS §9.3 + §10.1의 확정 사항을 반영한다.
 *
 * 업로드 영상의 재생 URI는 **여기 없다.** `content://` URI와 persistable 권한은
 * 그 기기의 그 설치본에만 유효하므로 기기 로컬 저장소가 들고 있어야 한다
 * (REQUIREMENTS §10.1, FIRESTORE_SCHEMA §3.3).
 */
@Serializable
public data class VideoPlan(
    val id: VideoPlanId,
    val transcript: Transcript,
    /** 자동 계산값. 세션 시작 화면에서 [targetReps]로 덮어쓸 수 있다 — §4.2. */
    val suggestedReps: Int,
    /** 실제 적용값. */
    val targetReps: Int,
    val autoReps: Boolean = true,
    val completedReps: Int = 0,
    /**
     * 다음에 이어서 할 자리. null이면 처음부터 한다.
     *
     * 한 문장을 끝낼 때마다 적는다 — 세션이 끝날 때만 적으면 전화가 오거나 앱이 꺼졌을 때
     * 그동안의 진행이 사라진다.
     */
    val resumeAt: PlanPosition? = null,
    /** 목표 회차를 끝까지 마친 횟수. 1 이상이면 "완료"로 보여 준다. */
    val completedRounds: Int = 0,
    /** 사용자가 "외웠어요"라고 한 문장의 번호. 이 문장은 듣지 않고 바로 말한다. */
    val memorizedSentences: Set<Int> = emptySet(),
) {
    val sentences: List<Sentence> get() = transcript.sentences

    /** §4.3 — 카운트 단위는 문장 1개다. 영상 전체 1회는 문장 수만큼으로 환산된다. */
    val countsPerFullPass: Int get() = sentences.size

    /** 외운 문장 수. 지금 있는 문장만 센다. */
    val memorizedCount: Int get() = sentences.indices.count { it in memorizedSentences }
}

/**
 * 세션 안의 자리 — 몇 번째 문장의 몇 번째 회차인가. 둘 다 0부터.
 *
 * 걸음 번호가 아니라 (문장, 회차)로 적는다. 반복 횟수를 바꾸면 걸음 번호는 전부 어긋나지만
 * 이 둘은 그대로 뜻이 통한다.
 */
@Serializable
public data class PlanPosition(
    val sentenceIndex: Int,
    val repIndex: Int,
)

/**
 * 영상 계획 ID. `sourceRef`에서 결정적으로 파생되므로 중복 등록이 구조적으로 막힌다
 * — FIRESTORE_SCHEMA §1.2.
 */
@Serializable
@JvmInline
public value class VideoPlanId(public val value: String) {
    override fun toString(): String = value

    public companion object {
        public fun of(source: VideoSource, sourceRef: String): VideoPlanId {
            val prefix = when (source) {
                VideoSource.YOUTUBE -> "YT"
                VideoSource.UPLOAD -> "UP"
                VideoSource.NOTE -> "NT"
            }
            return VideoPlanId("${prefix}_$sourceRef")
        }
    }
}

/**
 * 문장 식별자. 복습 큐와 문장 진행도가 같은 키를 공유해 조인이 필요 없다
 * — FIRESTORE_SCHEMA §1.2.
 */
@Serializable
@JvmInline
public value class SentenceId(public val value: String) {
    override fun toString(): String = value

    public companion object {
        public fun of(planId: VideoPlanId, sentenceIndex: Int): SentenceId =
            SentenceId("${planId.value}_s$sentenceIndex")
    }
}
