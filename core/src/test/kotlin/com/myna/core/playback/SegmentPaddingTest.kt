package com.myna.core.playback

import com.myna.core.model.TimestampUnit
import com.myna.core.sentence
import kotlin.test.Test
import kotlin.test.assertEquals

/** TRANSCRIPTION_SCHEMA §1.1 — 초 단위 타임스탬프의 경계 보정. */
class SegmentPaddingTest {

    @Test
    fun `초 단위 타임스탬프에는 앞뒤 300ms를 덧댄다`() {
        val segment = SegmentPadding.segmentFor(
            sentence(0, startMs = 5_000, endMs = 8_000),
            TimestampUnit.SECOND,
        )
        assertEquals(4_700, segment.startMs)
        assertEquals(8_300, segment.endMs)
    }

    @Test
    fun `밀리초 단위에는 패딩을 넣지 않는다`() {
        val segment = SegmentPadding.segmentFor(
            sentence(0, startMs = 5_000, endMs = 8_000),
            TimestampUnit.MILLISECOND,
        )
        assertEquals(5_000, segment.startMs)
        assertEquals(8_000, segment.endMs)
    }

    @Test
    fun `패딩이 영상 경계를 넘지 않는다`() {
        val segment = SegmentPadding.segmentFor(
            sentence(0, startMs = 100, endMs = 9_900),
            TimestampUnit.SECOND,
            videoDurationMs = 10_000,
        )
        assertEquals(0, segment.startMs)       // 음수로 내려가지 않는다
        assertEquals(10_000, segment.endMs)    // 영상 길이를 넘지 않는다
    }
}
