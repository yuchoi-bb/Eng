package com.myna.core.session

import com.myna.core.completeStep
import com.myna.core.plan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 장소와 회선 상태가 단계 구성을 바꾼다. */
class SessionOptionsTest {

    @Test
    fun `기본은 3단계다`() {
        val options = SessionOptions()
        assertEquals(
            listOf(ShadowingStage.LISTEN, ShadowingStage.SHADOW_WITH_TEXT, ShadowingStage.SHADOW_NO_TEXT),
            options.stages,
        )
    }

    @Test
    fun `원본을 들을 수 없으면 듣기 단계가 빠진다`() {
        // 들려줄 것이 없는데 듣기 단계를 세워 두면 빈 화면을 넘기게 된다.
        val options = SessionOptions(sourceAudioAvailable = false)
        assertEquals(
            listOf(ShadowingStage.SHADOW_WITH_TEXT, ShadowingStage.SHADOW_NO_TEXT),
            options.stages,
        )
    }

    @Test
    fun `무음 모드에서는 어느 단계에서도 녹음하지 않는다`() {
        val options = SessionOptions(voiceMode = VoiceMode.WHISPER)
        assertTrue(options.stages.none { options.requiresRecording(it) })
        assertFalse(options.recordsVoice)
    }

    @Test
    fun `소리 내는 모드에서는 말하기 단계만 녹음한다`() {
        val options = SessionOptions(voiceMode = VoiceMode.ALOUD)
        assertFalse(options.requiresRecording(ShadowingStage.LISTEN))
        assertTrue(options.requiresRecording(ShadowingStage.SHADOW_WITH_TEXT))
        assertTrue(options.requiresRecording(ShadowingStage.SHADOW_NO_TEXT))
    }

    @Test
    fun `오프라인에서도 카운트는 그대로 오른다`() {
        // 단계가 둘로 줄어도 "한 문장 완주 = 1카운트"는 유지된다.
        val engine = SessionEngine(plan(targetReps = 1), SessionOptions(sourceAudioAvailable = false))
        assertEquals(2, engine.stagesPerCount)
        assertEquals(ShadowingStage.SHADOW_WITH_TEXT, engine.currentStage)

        assertIs<SessionEvent.StageAdvanced>(engine.completeStage())
        assertIs<SessionEvent.CountCompleted>(engine.completeStage())
        assertEquals(1, engine.completedCounts)
    }

    @Test
    fun `무음 모드에서도 발화 시간은 쌓인다`() {
        // 조음 근육은 실제로 움직인다. 소리를 안 냈다고 연습이 아닌 것은 아니다.
        val engine = SessionEngine(plan(targetReps = 1), SessionOptions(voiceMode = VoiceMode.WHISPER))
        engine.completeStep()
        assertEquals(1, engine.completedCounts)
        assertTrue(engine.achievedSec > 0)
        assertFalse(engine.requiresRecording)
    }

    @Test
    fun `오프라인과 무음을 함께 쓸 수 있다`() {
        val engine = SessionEngine(
            plan(targetReps = 2),
            SessionOptions(sourceAudioAvailable = false, voiceMode = VoiceMode.WHISPER),
        )
        assertEquals(2, engine.stagesPerCount)
        assertFalse(engine.playsSource)
        while (!engine.isFinished) engine.completeStage()
        assertEquals(2, engine.completedCounts)
    }

    // ---------- 손대지 않고 듣고 따라 하기 ----------

    @Test
    fun `손대지 않는 모드는 녹음하지 않는다`() {
        val options = SessionOptions(handsFree = true)
        assertFalse(options.recordsVoice)
        assertTrue(options.stages.none { options.requiresRecording(it) })
    }

    @Test
    fun `들려준 길이만큼 기다린다`() {
        // 2초 문장을 0.75배로 들려주면 약 2.67초가 걸린다. 따라 말할 시간도 그만큼 + 시작 틈.
        val options = SessionOptions(handsFree = true)
        assertEquals(2666 + SessionOptions.FOLLOW_LEAD_MS, options.followPauseMs(2000, 0.75f))
    }

    @Test
    fun `따라 말할 시간을 늘릴 수 있다`() {
        val options = SessionOptions(handsFree = true, followFactor = 1.5f)
        assertEquals(3000 + SessionOptions.FOLLOW_LEAD_MS, options.followPauseMs(2000, 1f))
    }

    @Test
    fun `손대지 않는 모드에서도 한 걸음을 끝내면 1카운트다`() {
        val engine = SessionEngine(plan(targetReps = 2), SessionOptions(handsFree = true))
        engine.completeStep()
        assertEquals(1, engine.completedCounts)
        assertTrue(engine.achievedSec > 0)
    }
}
