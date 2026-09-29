package com.myna.core.session

import com.myna.core.completeStep
import com.myna.core.plan
import com.myna.core.sentence
import com.myna.core.transcript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 반복 횟수가 커지는 영상에서 언제 멈출 수 있는지 알려 주는 판정.
 *
 * 실제로 문장 17개짜리 쇼츠에서 카운트가 306이 나왔다. 한 카운트가 3단계이므로
 * 900번 넘는 상호작용이다. 목표를 채운 지점을 알려 주지 않으면 끝이 안 보인다.
 */
class SessionGoalTest {

    private val manySentences = transcript(
        speechStartMs = 0,
        speechEndMs = 17_000,
        sentences = (0 until 17).map { i ->
            sentence(i, startMs = i * 1000, endMs = i * 1000 + 1000)
        },
    )

    @Test
    fun `화면에 보여 줄 숫자는 카운트가 아니라 영상 반복 횟수다`() {
        // "남은 306회"는 사용자가 쓸 수 없는 숫자다. "18회 중 1회차"는 읽힌다.
        val engine = SessionEngine(plan(manySentences, targetReps = 18))
        assertEquals(306, engine.targetCounts)
        assertEquals(18, engine.totalReps)
        assertEquals(17, engine.sentenceCount)
        assertEquals(1, engine.currentRep)
        assertEquals(1, engine.currentSentenceNumber)
    }

    @Test
    fun `회차와 문장 번호가 진행에 따라 오른다`() {
        val engine = SessionEngine(plan(manySentences, targetReps = 18))
        // 한 문장을 완주하면 같은 문장의 다음 회차로 간다 (SessionPlan은 문장별로 펼친다)
        engine.completeStep()
        assertEquals(2, engine.currentRep)
        assertEquals(1, engine.currentSentenceNumber)
    }

    @Test
    fun `목표를 채우면 알린다`() {
        // 문장당 1초 × 3카운트 = 3초. 오늘 이미 297초를 했으면 3카운트에서 300초가 된다.
        val engine = SessionEngine(
            plan(manySentences, targetReps = 18),
            dailyAlreadySec = 297,
            dailyTargetSec = 300,
        )
        assertFalse(engine.dailyGoalMet)
        assertEquals(3, engine.remainingDailySec)

        repeat(3) { engine.completeStep() }
        assertTrue(engine.dailyGoalMet)
        assertEquals(0, engine.remainingDailySec)
    }

    @Test
    fun `목표를 채워도 세션을 강제로 끝내지 않는다`() {
        // §4.2.1 — 예산은 상한이 아니라 목표값이다. 더 하고 싶으면 계속할 수 있어야 한다.
        val engine = SessionEngine(
            plan(manySentences, targetReps = 18),
            dailyAlreadySec = 300,
            dailyTargetSec = 300,
        )
        assertTrue(engine.dailyGoalMet)
        assertFalse(engine.isFinished)

        engine.completeStep()
        assertEquals(1, engine.completedCounts)
    }

    @Test
    fun `목표를 주지 않으면 달성 판정을 하지 않는다`() {
        val engine = SessionEngine(plan(manySentences, targetReps = 18))
        assertFalse(engine.dailyGoalMet)
        assertEquals(0, engine.remainingDailySec)
    }
}
