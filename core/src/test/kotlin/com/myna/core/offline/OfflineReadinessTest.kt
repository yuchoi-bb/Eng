package com.myna.core.offline

import com.myna.core.model.SentenceId
import com.myna.core.plan
import com.myna.core.sentence
import com.myna.core.transcript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 회선이 없을 때 무엇을 연습할 수 있는가. */
class OfflineReadinessTest {

    private val threeSentences = plan(
        transcript(sentences = listOf(sentence(0), sentence(1), sentence(2))),
        targetReps = 1,
    )

    private fun cleared(vararg indices: Int): Set<String> =
        indices.map { SentenceId.of(threeSentences.id, it).value }.toSet()

    @Test
    fun `한 번도 안 들어 봤으면 오프라인 연습이 안 된다`() {
        // 못 들어 본 문장을 자막만 보고 따라 하는 것은 쉐도잉이 아니라 읽기다.
        val status = OfflineReadiness.statusFor(threeSentences, emptySet())
        assertEquals(OfflineAvailability.NOT_READY, status.availability)
        assertEquals(0, status.practicableSentences)
    }

    @Test
    fun `모든 문장을 들어 봤으면 그대로 복습한다`() {
        val status = OfflineReadiness.statusFor(threeSentences, cleared(0, 1, 2))
        assertEquals(OfflineAvailability.READY, status.availability)
        assertEquals(3, status.practicableSentences)
    }

    @Test
    fun `일부만 들어 봤으면 부분 연습이다`() {
        val status = OfflineReadiness.statusFor(threeSentences, cleared(0, 2))
        assertEquals(OfflineAvailability.PARTIAL, status.availability)
        assertEquals(2, status.practicableSentences)
        assertEquals(3, status.totalSentences)
    }

    @Test
    fun `연습 가능한 문장만 남기고 번호를 다시 매긴다`() {
        // 덜어 내고 번호를 그대로 두면 SentenceId가 어긋나 진행도가 엉뚱한 문장에 붙는다.
        val practice = OfflineReadiness.practicablePlan(threeSentences, cleared(0, 2))
        assertEquals(2, practice!!.sentences.size)
        assertEquals(listOf(0, 1), practice.sentences.map { it.index })
    }

    @Test
    fun `전부 연습 가능하면 원본 계획을 그대로 쓴다`() {
        val practice = OfflineReadiness.practicablePlan(threeSentences, cleared(0, 1, 2))
        assertEquals(threeSentences, practice)
    }

    @Test
    fun `연습할 문장이 없으면 계획을 만들지 않는다`() {
        assertNull(OfflineReadiness.practicablePlan(threeSentences, emptySet()))
    }
}
