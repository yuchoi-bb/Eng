package com.myna.core.plan

import com.myna.core.model.PlanPosition
import com.myna.core.model.SentenceId
import com.myna.core.model.VideoPlanId
import com.myna.core.plan
import com.myna.core.sentence
import com.myna.core.transcript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 문장을 고친 뒤에도 외운 문장과 이어서 하기 자리가 같은 문장을 가리켜야 한다. */
class PlanEditTest {

    private val fourSentences = plan(
        transcript(
            speechStartMs = 0,
            speechEndMs = 8000,
            sentences = (0 until 4).map { sentence(it, startMs = it * 2000, endMs = it * 2000 + 2000) },
        ),
        targetReps = 5,
    )

    /** 1번 문장(0부터)을 지운 결과. */
    private val withoutSecond = transcript(
        speechStartMs = 0,
        speechEndMs = 8000,
        sentences = listOf(0, 2, 3).mapIndexed { i, origin ->
            sentence(i, startMs = origin * 2000, endMs = origin * 2000 + 2000)
        },
    )
    private val origins = listOf(0, 2, 3)

    @Test
    fun `외운 문장 표시가 새 번호로 옮겨 간다`() {
        val old = fourSentences.copy(memorizedSentences = setOf(1, 3))
        val edited = PlanEdit.rebase(old, withoutSecond, origins, dailyTargetSec = 300)
        // 1번은 지워졌다. 3번은 이제 2번이다.
        assertEquals(setOf(2), edited.memorizedSentences)
    }

    @Test
    fun `남아 있는 문장이면 같은 회차에서 잇는다`() {
        val old = fourSentences.copy(resumeAt = PlanPosition(2, 3))
        val edited = PlanEdit.rebase(old, withoutSecond, origins, dailyTargetSec = 300)
        assertEquals(PlanPosition(1, 3), edited.resumeAt)
    }

    @Test
    fun `하던 문장을 지웠으면 다음 문장의 1회차에서 잇는다`() {
        val old = fourSentences.copy(resumeAt = PlanPosition(1, 3))
        val edited = PlanEdit.rebase(old, withoutSecond, origins, dailyTargetSec = 300)
        assertEquals(PlanPosition(1, 0), edited.resumeAt)
    }

    @Test
    fun `뒤에 남은 문장이 없으면 처음부터다`() {
        val old = fourSentences.copy(resumeAt = PlanPosition(3, 1))
        val edited = PlanEdit.rebase(old, withoutSecond, listOf(0, 1, 2), dailyTargetSec = 300)
        assertNull(edited.resumeAt)
    }

    @Test
    fun `새로 추가한 문장은 외운 문장이 아니다`() {
        val old = fourSentences.copy(memorizedSentences = setOf(0))
        val edited = PlanEdit.rebase(old, withoutSecond, listOf(null, 0, 3), dailyTargetSec = 300)
        assertEquals(setOf(1), edited.memorizedSentences)
    }

    @Test
    fun `수동 횟수는 그대로 두고 누적 기록도 그대로다`() {
        val old = fourSentences.copy(autoReps = false, targetReps = 30, completedReps = 12, completedRounds = 1)
        val edited = PlanEdit.rebase(old, withoutSecond, origins, dailyTargetSec = 300)
        assertEquals(30, edited.targetReps)
        assertEquals(12, edited.completedReps)
        assertEquals(1, edited.completedRounds)
    }

    @Test
    fun `완주 기록은 이 영상 것만 새 번호로 옮긴다`() {
        val other = SentenceId.of(VideoPlanId("YT_other"), 1).value
        val cleared = setOf(
            SentenceId.of(fourSentences.id, 1).value,
            SentenceId.of(fourSentences.id, 3).value,
            other,
        )
        val remapped = PlanEdit.remapClearedIds(cleared, fourSentences, origins)
        assertEquals(setOf(SentenceId.of(fourSentences.id, 2).value, other), remapped)
    }
}
