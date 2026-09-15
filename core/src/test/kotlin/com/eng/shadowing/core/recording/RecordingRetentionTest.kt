package com.eng.shadowing.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** REQUIREMENTS §4.5 — 최근 3회분 순환 삭제, 단 최초 녹음 1개는 영구 보관. */
class RecordingRetentionTest {

    private fun rec(n: Int) = RecordingRef(path = "rec$n.m4a", recordedAtEpochMs = n.toLong())

    @Test
    fun `첫 녹음은 영구 보관 대상으로 승격되고 업로드된다`() {
        // F-9 성장 기록이 여기 의존한다 — §7.4.
        val decision = RecordingRetention.apply(existing = emptyList(), incoming = rec(1))

        assertNotNull(decision.uploadAsFirstRecording)
        assertTrue(decision.keep.single().isFirstEver)
        assertTrue(decision.delete.isEmpty())
    }

    @Test
    fun `두 번째 이후 녹음은 영구 보관으로 승격되지 않는다`() {
        val first = rec(1).copy(isFirstEver = true)
        val decision = RecordingRetention.apply(existing = listOf(first), incoming = rec(2))

        assertNull(decision.uploadAsFirstRecording)
        assertEquals(1, decision.keep.count { it.isFirstEver })
    }

    @Test
    fun `순환 녹음은 최근 3개만 남는다`() {
        val first = rec(1).copy(isFirstEver = true)
        val existing = listOf(first, rec(2), rec(3), rec(4))

        val decision = RecordingRetention.apply(existing, incoming = rec(5))

        // 영구 보관 1개 + 최근 3개
        assertEquals(4, decision.keep.size)
        assertEquals(listOf("rec2.m4a"), decision.delete.map { it.path })
        assertTrue(decision.keep.any { it.path == "rec1.m4a" }) // 최초 녹음은 살아남는다
    }

    @Test
    fun `최초 녹음은 오래돼도 지워지지 않는다`() {
        val first = rec(1).copy(isFirstEver = true)
        var existing = listOf(first)
        repeat(10) { i ->
            val decision = RecordingRetention.apply(existing, incoming = rec(i + 2))
            existing = decision.keep
        }
        assertTrue(existing.any { it.path == "rec1.m4a" && it.isFirstEver })
        assertEquals(RecordingRetention.KEEP_RECENT + 1, existing.size)
    }
}
