package com.eng.shadowing.core.model

/**
 * 한 영상의 학습 계획. REQUIREMENTS §9.3 + §10.1의 확정 사항을 반영한다.
 *
 * 업로드 영상의 재생 URI는 **여기 없다.** `content://` URI와 persistable 권한은
 * 그 기기의 그 설치본에만 유효하므로 기기 로컬 저장소가 들고 있어야 한다
 * (REQUIREMENTS §10.1, FIRESTORE_SCHEMA §3.3).
 */
public data class VideoPlan(
    val id: VideoPlanId,
    val transcript: Transcript,
    /** 자동 계산값. 세션 시작 화면에서 [targetReps]로 덮어쓸 수 있다 — §4.2. */
    val suggestedReps: Int,
    /** 실제 적용값. */
    val targetReps: Int,
    val autoReps: Boolean = true,
    val completedReps: Int = 0,
) {
    val sentences: List<Sentence> get() = transcript.sentences

    /** §4.3 — 카운트 단위는 문장 1개다. 영상 전체 1회는 문장 수만큼으로 환산된다. */
    val countsPerFullPass: Int get() = sentences.size
}

/**
 * 영상 계획 ID. `sourceRef`에서 결정적으로 파생되므로 중복 등록이 구조적으로 막힌다
 * — FIRESTORE_SCHEMA §1.2.
 */
@JvmInline
public value class VideoPlanId(public val value: String) {
    override fun toString(): String = value

    public companion object {
        public fun of(source: VideoSource, sourceRef: String): VideoPlanId {
            val prefix = when (source) {
                VideoSource.YOUTUBE -> "YT"
                VideoSource.UPLOAD -> "UP"
            }
            return VideoPlanId("${prefix}_$sourceRef")
        }
    }
}

/**
 * 문장 식별자. 복습 큐와 문장 진행도가 같은 키를 공유해 조인이 필요 없다
 * — FIRESTORE_SCHEMA §1.2.
 */
@JvmInline
public value class SentenceId(public val value: String) {
    override fun toString(): String = value

    public companion object {
        public fun of(planId: VideoPlanId, sentenceIndex: Int): SentenceId =
            SentenceId("${planId.value}_s$sentenceIndex")
    }
}
