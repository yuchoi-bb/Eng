package com.myna.core.session

import com.myna.core.model.BreathGroup
import com.myna.core.model.VideoType
import com.myna.core.plan
import com.myna.core.sentence
import com.myna.core.transcript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** REQUIREMENTS §4.3 / §4.5 / §7.2. */
class SessionEngineTest {

    @Test
    fun `3단계를 모두 통과해야 카운트가 1 오른다`() {
        // §7.2 — 이 규칙이 반복 횟수의 질을 결정한다.
        val engine = SessionEngine(plan(targetReps = 1))
        assertEquals(3, engine.stagesPerCount)

        assertEquals(ShadowingStage.LISTEN, engine.currentStage)
        assertEquals(0, engine.completedCounts)

        assertIs<SessionEvent.StageAdvanced>(engine.completeStage())
        assertEquals(ShadowingStage.SHADOW_WITH_TEXT, engine.currentStage)
        assertEquals(0, engine.completedCounts)

        assertIs<SessionEvent.StageAdvanced>(engine.completeStage())
        assertEquals(ShadowingStage.SHADOW_NO_TEXT, engine.currentStage)
        assertEquals(0, engine.completedCounts)

        val event = engine.completeStage()
        assertIs<SessionEvent.CountCompleted>(event)
        assertEquals(1, engine.completedCounts)
    }

    @Test
    fun `자막은 3단계에서만 숨는다`() {
        // §7.2 표.
        val engine = SessionEngine(plan(targetReps = 1))
        assertTrue(engine.showsSubtitle)      // 1단계 듣기만 — 표시
        assertFalse(engine.requiresRecording) // 듣기만이므로 녹음 없음

        engine.completeStage()
        assertTrue(engine.showsSubtitle)      // 2단계 보면서 따라 말하기 — 표시
        assertTrue(engine.requiresRecording)

        engine.completeStage()
        assertFalse(engine.showsSubtitle)     // 3단계 자막 끄고 — 숨김
        assertTrue(engine.requiresRecording)
    }

    @Test
    fun `카운트 단위는 문장 1개다`() {
        // §4.3 — 영상 전체 1회는 문장 수만큼 카운트로 환산된다.
        val threeSentences = transcript(
            sentences = listOf(sentence(0), sentence(1), sentence(2)),
        )
        val engine = SessionEngine(plan(threeSentences, targetReps = 2))
        assertEquals(6, engine.targetCounts) // 문장 3개 × 2회
    }

    @Test
    fun `호흡 조각은 카운트에 포함되지 않는다`() {
        // §4.4 SINGLE — 부분 반복은 발판이지 카운트가 아니다.
        val single = transcript(
            type = VideoType.SINGLE,
            sentences = listOf(
                sentence(
                    0,
                    breathGroups = listOf(
                        BreathGroup("What are you up to", 0, 1200),
                        BreathGroup("this weekend?", 1200, 2000),
                    ),
                ),
            ),
        )
        val engine = SessionEngine(plan(single, targetReps = 3))

        assertEquals(3, engine.targetCounts) // 조각 2개는 빠진다

        // 첫 걸음은 호흡 조각이다.
        repeat(engine.stagesPerCount - 1) { engine.completeStage() }
        assertIs<SessionEvent.BreathGroupCompleted>(engine.completeStage())
        assertEquals(0, engine.completedCounts)
    }

    @Test
    fun `호흡 조각은 각 문장의 첫 회차에만 나온다`() {
        val single = transcript(
            type = VideoType.SINGLE,
            sentences = listOf(
                sentence(0, breathGroups = listOf(BreathGroup("a", 0, 500), BreathGroup("b", 500, 2000))),
            ),
        )
        val steps = SessionPlan.expand(plan(single, targetReps = 4))
        assertEquals(2, steps.count { it.unit is PracticeUnit.Breath })
        assertEquals(4, steps.count { it.unit == PracticeUnit.FullSentence })
    }

    @Test
    fun `제시 횟수를 그대로 따르면 일일 목표에 도달한다`() {
        // §4.2와 §4.2.1의 정합성 검사 — 이 테스트가 achievedSec의 정의를 고정한다.
        //
        // suggestedReps = ceil(dailyTargetSec / videoSpeechSec)는 "1회차 = videoSpeechSec만큼
        // 말한다"를 전제로 세워진 식이다. 그러므로 제시 횟수를 그대로 돌렸을 때
        // achievedSec이 목표 언저리에 떨어져야 두 절이 맞는다.
        val dailyTargetSec = 300
        val sentences = (0 until 5).map { i ->
            sentence(i, startMs = i * 8_000, endMs = i * 8_000 + 8_000) // 문장당 8초, 총 40초
        }
        val t = transcript(speechStartMs = 0, speechEndMs = 40_000, sentences = sentences)

        val reps = RepsCalculator.suggestedReps(dailyTargetSec, t)
        assertEquals(8, reps)

        val engine = SessionEngine(plan(t, targetReps = reps))
        while (!engine.isFinished) engine.completeStage()

        // 8회 × 40초 = 320초. 목표 300초를 채우고, 한 회차 이상 넘기지 않는다.
        assertEquals(320, engine.achievedSec)
        assertTrue(engine.achievedSec >= dailyTargetSec)
        assertTrue(engine.achievedSec < dailyTargetSec + 40)
    }

    @Test
    fun `남은 횟수는 진행에 따라 줄어든다`() {
        // §4.5 — 세그먼트 링 진행바가 쓰는 값.
        val engine = SessionEngine(plan(targetReps = 3))
        assertEquals(3, engine.remainingCounts)

        repeat(engine.stagesPerCount) { engine.completeStage() }
        assertEquals(2, engine.remainingCounts)
    }

    @Test
    fun `모든 걸음을 끝내면 세션이 종료된다`() {
        val engine = SessionEngine(plan(targetReps = 2))
        repeat(engine.stagesPerCount * 2) { engine.completeStage() }

        assertTrue(engine.isFinished)
        assertEquals(2, engine.completedCounts)
        assertIs<SessionEvent.SessionFinished>(engine.completeStage())
    }
}
