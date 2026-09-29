package com.myna.core.session

import com.myna.core.completeStep
import com.myna.core.model.BreathGroup
import com.myna.core.model.PlanPosition
import com.myna.core.model.VideoType
import com.myna.core.plan
import com.myna.core.sentence
import com.myna.core.transcript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 회차별 단계 구성, 외운 문장, 이어서 하기.
 *
 * 한 회차마다 같은 소리를 세 번 듣던 구성을 줄이고, 외운 문장은 듣지 않고 말하게 하며,
 * 멈춘 자리에서 다시 시작하게 한다.
 */
class SessionProgressTest {

    private val threeSentences = transcript(
        sentences = listOf(
            sentence(0, startMs = 0, endMs = 2000),
            sentence(1, startMs = 2000, endMs = 4000),
            sentence(2, startMs = 4000, endMs = 6000),
        ),
    )

    // ---------- 회차별 단계 ----------

    @Test
    fun `처음 듣는 회차만 듣기 단계가 있다`() {
        val engine = SessionEngine(plan(targetReps = 3))
        assertEquals(ShadowingStage.LISTEN, engine.currentStage)
        assertEquals(3, engine.stagesPerCount)

        engine.completeStep()
        // 2회차 — 말하기 직전에 원본을 들려주므로 "듣기만"을 따로 두지 않는다.
        assertEquals(2, engine.currentRep)
        assertEquals(ShadowingStage.SHADOW_WITH_TEXT, engine.currentStage)
        assertEquals(2, engine.stagesPerCount)
        assertTrue(engine.playsBeforeStage)
    }

    @Test
    fun `회차에 따라 단계 수가 달라도 한 걸음 완주가 1카운트다`() {
        val engine = SessionEngine(plan(targetReps = 3))
        repeat(3) { assertIs<SessionEvent.CountCompleted>(engine.completeStep()) }
        assertEquals(3, engine.completedCounts)
        assertTrue(engine.isFinished)
    }

    // ---------- 외운 문장 ----------

    @Test
    fun `외운 문장은 듣지 않고 자막 없이 바로 말한다`() {
        val videoPlan = plan(targetReps = 2).copy(memorizedSentences = setOf(0))
        val engine = SessionEngine(videoPlan)

        assertEquals(ShadowingStage.RECALL, engine.currentStage)
        assertEquals(1, engine.stagesPerCount)
        assertFalse(engine.showsSubtitle)
        assertFalse(engine.playsBeforeStage)
        assertTrue(engine.requiresRecording)

        // 한 번 말하면 1카운트다. 발화 시간도 그대로 쌓인다.
        val event = engine.completeStage()
        assertIs<SessionEvent.CountCompleted>(event)
        assertEquals(2, event.achievedSecDelta)
    }

    @Test
    fun `외운 문장은 3회차마다 말한 뒤에 원본을 들려준다`() {
        val videoPlan = plan(targetReps = 6).copy(memorizedSentences = setOf(0))
        val engine = SessionEngine(videoPlan)

        val checks = (1..6).map { engine.checksAfterSpeaking.also { engine.completeStep() } }
        assertEquals(listOf(false, false, true, false, false, true), checks)
    }

    @Test
    fun `원본을 들을 수 없으면 비교 재생도 없다`() {
        val videoPlan = plan(targetReps = 3).copy(memorizedSentences = setOf(0))
        val engine = SessionEngine(videoPlan, SessionOptions(sourceAudioAvailable = false))
        repeat(2) { engine.completeStep() }
        assertFalse(engine.checksAfterSpeaking)
    }

    @Test
    fun `외웠어요를 누르면 하던 걸음은 그대로 끝내고 다음 회차부터 바뀐다`() {
        val engine = SessionEngine(plan(targetReps = 3))
        engine.completeStage() // 듣기 → 보고 말하기
        engine.markMemorized(0)

        assertEquals(ShadowingStage.SHADOW_WITH_TEXT, engine.currentStage)
        assertIs<SessionEvent.StageAdvanced>(engine.completeStage())
        assertIs<SessionEvent.CountCompleted>(engine.completeStage())

        assertEquals(ShadowingStage.RECALL, engine.currentStage)
        assertTrue(engine.isCurrentSentenceMemorized)
    }

    @Test
    fun `헷갈려요를 누르면 이번 걸음을 듣고 따라 하기로 다시 한다`() {
        val videoPlan = plan(targetReps = 3).copy(memorizedSentences = setOf(0))
        val engine = SessionEngine(videoPlan)
        engine.completeStep() // 1회차는 바로 말하기로 끝냈다

        assertTrue(engine.unmarkMemorized(0))
        assertEquals(ShadowingStage.SHADOW_WITH_TEXT, engine.currentStage)
        assertEquals(1, engine.stageNumber)
        // 단계만 바뀌고 이미 올린 카운트는 그대로다.
        assertEquals(1, engine.completedCounts)
        assertFalse(engine.isCurrentSentenceMemorized)
    }

    @Test
    fun `외운 문장의 호흡 조각은 건너뛴다`() {
        val single = transcript(
            type = VideoType.SINGLE,
            sentences = listOf(
                sentence(0, breathGroups = listOf(BreathGroup("a", 0, 500), BreathGroup("b", 500, 2000))),
            ),
        )
        val engine = SessionEngine(plan(single, targetReps = 2).copy(memorizedSentences = setOf(0)))
        assertIs<SessionEvent.CountCompleted>(engine.completeStep())
        assertIs<SessionEvent.CountCompleted>(engine.completeStep())
        assertTrue(engine.isFinished)
    }

    // ---------- 이어서 하기 ----------

    @Test
    fun `멈춘 자리에서 이어서 한다`() {
        val engine = SessionEngine(plan(threeSentences, targetReps = 4), startAt = PlanPosition(1, 2))

        assertEquals(2, engine.currentSentenceNumber)
        assertEquals(3, engine.currentRep)
        // 이어서 시작한 걸음은 처음 듣는 회차가 아니다.
        assertEquals(ShadowingStage.SHADOW_WITH_TEXT, engine.currentStage)
        // 진행바는 영상 전체 기준이다 — 문장 1의 4회 + 문장 2의 2회.
        assertEquals(6, engine.overallCompletedCounts)
        assertEquals(6, engine.remainingCounts)
        // 이 세션에서 올린 카운트는 0부터 센다. 오늘 로그에 두 번 더하지 않는다.
        assertEquals(0, engine.completedCounts)
    }

    @Test
    fun `카운트마다 다음 자리를 알려 준다`() {
        val engine = SessionEngine(plan(threeSentences, targetReps = 2))
        val first = engine.completeStep()
        assertIs<SessionEvent.CountCompleted>(first)
        assertEquals(PlanPosition(0, 1), first.nextPosition)

        val second = engine.completeStep()
        assertIs<SessionEvent.CountCompleted>(second)
        assertEquals(PlanPosition(1, 0), second.nextPosition)
    }

    @Test
    fun `마지막 걸음을 끝내면 다음 자리는 없다`() {
        val engine = SessionEngine(plan(threeSentences, targetReps = 2), startAt = PlanPosition(2, 1))
        val last = engine.completeStep()
        assertIs<SessionEvent.CountCompleted>(last)
        assertNull(last.nextPosition)
        assertTrue(engine.isFinished)
    }

    @Test
    fun `반복 횟수를 줄여 자리가 계획 밖이면 처음부터 한다`() {
        // 문장 2의 8회차에서 멈췄는데 반복 횟수를 4회로 줄였다 — 문장 3으로 넘어간다.
        val shrunk = SessionEngine(plan(threeSentences, targetReps = 4), startAt = PlanPosition(1, 7))
        assertEquals(3, shrunk.currentSentenceNumber)
        assertEquals(1, shrunk.currentRep)

        // 마지막 문장 뒤라면 처음부터.
        val beyond = SessionEngine(plan(threeSentences, targetReps = 4), startAt = PlanPosition(2, 9))
        assertEquals(1, beyond.currentSentenceNumber)
        assertEquals(1, beyond.currentRep)
    }

    @Test
    fun `첫 회차에서 이어 가면 호흡 조각부터 한다`() {
        val single = transcript(
            type = VideoType.SINGLE,
            sentences = listOf(
                sentence(0),
                sentence(1, breathGroups = listOf(BreathGroup("a", 0, 500), BreathGroup("b", 500, 2000))),
            ),
        )
        val engine = SessionEngine(plan(single, targetReps = 2), startAt = PlanPosition(1, 0))
        assertEquals(2, engine.currentSentenceNumber)
        assertIs<SessionEvent.BreathGroupCompleted>(engine.completeStep())
    }

    // ---------- 오프라인·재생 거부 ----------

    @Test
    fun `오프라인 복습은 들어 본 문장만 원래 번호로 돈다`() {
        val engine = SessionEngine(
            plan(threeSentences, targetReps = 1),
            SessionOptions(sourceAudioAvailable = false),
            sentenceFilter = setOf(0, 2),
        )
        assertEquals(2, engine.targetCounts)
        engine.completeStep()
        assertEquals(3, engine.currentSentenceNumber) // 번호를 다시 매기지 않는다
    }

    @Test
    fun `재생이 막히면 듣기 단계를 빼고 말하기부터 다시 한다`() {
        val engine = SessionEngine(plan(threeSentences, targetReps = 1))
        assertEquals(ShadowingStage.LISTEN, engine.currentStage)

        engine.dropSourceAudio()
        assertEquals(ShadowingStage.SHADOW_WITH_TEXT, engine.currentStage)
        assertFalse(engine.playsBeforeStage)

        // 다음 문장의 첫 회차에도 듣기 단계가 없다.
        engine.completeStep()
        assertEquals(2, engine.currentSentenceNumber)
        assertEquals(ShadowingStage.SHADOW_WITH_TEXT, engine.currentStage)
    }
}
