package com.myna.core.notes

import com.myna.core.model.VideoSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 막힌 순간의 메모가 연습 대상이 되기까지. */
class NotePracticeTest {

    private fun note(english: String? = null) = FieldNote(
        id = "n1",
        koreanMemo = "택시에서 목적지 바꿔 달라고 못 함",
        situation = NoteSituation.TAXI,
        englishText = english,
        createdAtEpochMs = 0,
    )

    @Test
    fun `영어 문장을 채우기 전에는 연습 대상이 아니다`() {
        assertNull(NotePractice.toPlan(note(), dailyTargetSec = 300))
        assertNull(NotePractice.toPlan(note("   "), dailyTargetSec = 300))
    }

    @Test
    fun `채워진 메모는 다른 계획과 똑같이 취급된다`() {
        val plan = NotePractice.toPlan(note("Could you change the destination?"), 300)!!

        assertEquals(VideoSource.NOTE, plan.transcript.source)
        assertEquals("NT_n1", plan.id.value)
        assertEquals(1, plan.sentences.size)
        // 한글 메모가 그대로 뜻이 된다 — 막혔을 때 적은 말이 곧 하려던 말이다.
        assertEquals("택시에서 목적지 바꿔 달라고 못 함", plan.sentences.single().translationKo)
    }

    @Test
    fun `검증을 거치므로 키워드가 비어 있지 않다`() {
        // 손으로 적은 문장이라고 검증을 건너뛰면 L2가 채점 기준을 잃는다.
        val plan = NotePractice.toPlan(note("Could you change the destination?"), 300)!!
        assertTrue(plan.sentences.single().keywords.isNotEmpty())
    }

    @Test
    fun `발화 시간은 단어 수로 어림잡는다`() {
        // 원본이 없으니 실제 길이를 알 수 없다. 일일 예산에 더할 값은 필요하다.
        assertEquals(NotePractice.MIN_SPEECH_MS, NotePractice.estimateSpeechMs("Sorry"))
        assertEquals(6 * NotePractice.MS_PER_WORD, NotePractice.estimateSpeechMs("Could you change the destination please"))
    }

    @Test
    fun `반복 횟수가 일일 목표에서 나온다`() {
        val plan = NotePractice.toPlan(note("Could you change the destination?"), 300)!!
        // 5단어 = 2.5초 → ceil(300/3) = 100 → 상한 50
        assertEquals(50, plan.targetReps)
    }

    @Test
    fun `해결 여부는 영어 문장 유무로 판정한다`() {
        assertTrue(!note().isResolved)
        assertTrue(note("I need a receipt.").isResolved)
    }
}
