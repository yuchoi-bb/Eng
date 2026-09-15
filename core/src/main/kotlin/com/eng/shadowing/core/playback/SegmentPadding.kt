package com.eng.shadowing.core.playback

import com.eng.shadowing.core.model.Sentence
import com.eng.shadowing.core.model.TimestampUnit

/** 재생할 구간. 패딩이 적용된 최종 값이다. */
public data class PlaybackSegment(val startMs: Int, val endMs: Int) {
    val durationMs: Int get() = endMs - startMs
}

/**
 * 구간 반복 재생의 경계 계산. TRANSCRIPTION_SCHEMA §1.1.
 *
 * 유튜브 경로는 문장 경계가 초 단위라 그대로 재생하면 앞뒤가 잘린다.
 * **스키마에는 원본 값을 저장하고, 재생할 때만 패딩을 적용한다.**
 */
public object SegmentPadding {

    public const val PADDING_MS: Int = 300

    public fun segmentFor(
        sentence: Sentence,
        timestampUnit: TimestampUnit,
        videoDurationMs: Int? = null,
    ): PlaybackSegment {
        val padding = if (timestampUnit == TimestampUnit.SECOND) PADDING_MS else 0
        val start = (sentence.startMs - padding).coerceAtLeast(0)
        val rawEnd = sentence.endMs + padding
        val end = if (videoDurationMs != null) rawEnd.coerceAtMost(videoDurationMs) else rawEnd
        return PlaybackSegment(start, end.coerceAtLeast(start))
    }
}
