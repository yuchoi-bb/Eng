package com.eng.shadowing.core.recording

/** 한 문장에 속한 녹음 하나. `path`는 기기 로컬 경로다. */
public data class RecordingRef(
    val path: String,
    val recordedAtEpochMs: Long,
    val isFirstEver: Boolean = false,
)

/** 새 녹음을 받았을 때 무엇을 남기고 무엇을 지울지. */
public data class RetentionDecision(
    val keep: List<RecordingRef>,
    val delete: List<RecordingRef>,
    /** F-9 성장 기록용으로 Cloud Storage에 올려야 하는 녹음. */
    val uploadAsFirstRecording: RecordingRef?,
)

/**
 * 녹음 보관 규칙. REQUIREMENTS §4.5.
 *
 * 최근 3회분만 남기고 순환 삭제하되, **각 문장의 최초 녹음 1개는 영구 보관**한다
 * (F-9 성장 기록이 여기 의존한다 — §7.4).
 */
public object RecordingRetention {

    public const val KEEP_RECENT: Int = 3

    /**
     * @param existing 이 문장의 기존 녹음. 순서는 상관없다.
     * @param incoming 방금 끝난 녹음.
     */
    public fun apply(existing: List<RecordingRef>, incoming: RecordingRef): RetentionDecision {
        val hasPermanent = existing.any { it.isFirstEver }
        val promoted = if (hasPermanent) incoming else incoming.copy(isFirstEver = true)

        val all = existing + promoted
        val (permanent, rotating) = all.partition { it.isFirstEver }

        // 최근 것부터 KEEP_RECENT개만 남긴다.
        val sorted = rotating.sortedByDescending { it.recordedAtEpochMs }
        val keptRotating = sorted.take(KEEP_RECENT)
        val deleted = sorted.drop(KEEP_RECENT)

        return RetentionDecision(
            keep = permanent + keptRotating,
            delete = deleted,
            uploadAsFirstRecording = promoted.takeIf { !hasPermanent },
        )
    }
}
