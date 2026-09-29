package com.myna.ui.session

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.myna.core.model.PlanPosition
import com.myna.core.model.Sentence
import com.myna.core.model.SentenceId
import com.myna.core.model.VideoPlan
import com.myna.core.playback.SegmentPadding
import com.myna.core.session.PracticeUnit
import com.myna.core.session.SessionEngine
import com.myna.core.session.SessionEvent
import com.myna.core.session.SessionOptions
import com.myna.core.session.ShadowingStage
import com.myna.media.RecordingPlayer
import com.myna.media.SentenceRecorder
import com.myna.media.SourcePlayback
import java.io.File

internal data class SessionUiState(
    val stage: ShadowingStage = ShadowingStage.SHADOW_WITH_TEXT,
    val sentenceText: String = "",
    val translationKo: String = "",
    val transliterationKo: String? = null,
    val showSubtitle: Boolean = true,
    val recording: Boolean = false,
    val hasRecording: Boolean = false,
    val completedCounts: Int = 0,
    val targetCounts: Int = 0,
    val remainingCounts: Int = 0,
    val finished: Boolean = false,
    /** 읽히는 진행 표시 — 카운트(306회)가 아니라 회차로 보여 준다. */
    val currentRep: Int = 1,
    val totalReps: Int = 1,
    val currentSentenceNumber: Int = 1,
    val sentenceCount: Int = 1,
    /** 오늘 목표를 채웠는가. 채웠으면 끝낼 수 있다고 알린다. */
    val dailyGoalMet: Boolean = false,
    val remainingDailySec: Int = 0,
    /** 원본 재생이 거부됐을 때 보여 줄 안내. */
    val playbackNotice: String? = null,
    val playsSource: Boolean = true,
    /** 이번 걸음의 몇 번째 단계인가, 모두 몇 단계인가. 회차와 외운 문장 여부에 따라 1~3이다. */
    val stageNumber: Int = 1,
    val stageCount: Int = 3,
    /** 지금 문장을 외웠다고 표시했는가. */
    val memorized: Boolean = false,
    /** 말한 뒤에 원본을 들려주는 중이다 (외운 문장의 비교 재생). */
    val checking: Boolean = false,
    /** 영상 전체 기준 진행 — 이어서 하기로 건너뛴 앞부분까지 포함한다. */
    val overallCompletedCounts: Int = 0,
    val memorizedCount: Int = 0,
) {
    val progressFraction: Float
        get() = if (targetCounts == 0) 0f else overallCompletedCounts.toFloat() / targetCounts
}

/**
 * 세션 화면의 상태 보유자.
 *
 * 진행 판정은 전부 [SessionEngine]이 한다. 여기서는 그 결정을 재생기와 녹음기에
 * 연결하기만 한다 — 카운트 규칙이 UI에 스며들면 §7.2의 "3단계 완주 = 1카운트"가
 * 화면마다 달라진다.
 */
internal class SessionController(
    context: Context,
    private val plan: VideoPlan,
    private val playbackRate: Float,
    private val options: SessionOptions = SessionOptions(),
    dailyAlreadySec: Int = 0,
    dailyTargetSec: Int = 0,
    /** 이어서 할 자리. null이면 처음부터. */
    startAt: PlanPosition? = null,
    /** 오프라인 복습에서 들어 본 문장만 돌 때. null이면 전부. */
    sentenceFilter: Set<Int>? = null,
    /** 한 문장을 끝낼 때마다 — 그 자리에서 저장한다. */
    private val onCountCompleted: (SessionEvent.CountCompleted) -> Unit = {},
    /** 외웠어요 / 헷갈려요. */
    private val onMemorizedChanged: (sentenceIndex: Int, memorized: Boolean) -> Unit = { _, _ -> },
    /** 다음 문장으로 건너뛰었다. 이어서 할 자리를 저장한다. null이면 마지막 문장이었다. */
    private val onSentenceSkipped: (next: PlanPosition?) -> Unit = {},
) {
    private val recorder = SentenceRecorder(context)
    private val recordingPlayer = RecordingPlayer()
    private val engine = SessionEngine(
        plan = plan,
        options = options,
        dailyAlreadySec = dailyAlreadySec,
        dailyTargetSec = dailyTargetSec,
        startAt = startAt,
        sentenceFilter = sentenceFilter,
    )

    /**
     * 원본을 재생할 수 있는가.
     *
     * 유튜브가 임베드 재생을 거부하면 재생 완료 콜백이 오지 않아 세션이 듣기 단계에서
     * 굳는다. 그때 엔진이 듣기 단계를 빼고 자막만 보는 방식으로 넘어간다 — 오프라인 복습과
     * 같은 경로다.
     */
    private val playsSource: Boolean get() = engine.playsSource

    /** 말한 뒤 원본을 들려주는 중인가. */
    private var isChecking = false

    /**
     * 원본 재생기. 화면이 뷰를 만든 뒤에 붙인다 — 업로드는 ExoPlayer, 유튜브는
     * IFrame Player를 쓰는데 둘 다 안드로이드 뷰가 먼저 있어야 만들어진다.
     */
    private var source: SourcePlayback? = null

    private val _uiState: MutableState<SessionUiState> = mutableStateOf(snapshot())
    val uiState: State<SessionUiState> get() = _uiState

    val achievedSec: Int get() = engine.achievedSec
    val completedCounts: Int get() = engine.completedCounts

    private var started = false

    /** snapshot()이 자기 자신을 읽지 않도록 녹음 여부는 따로 들고 있는다. */
    private var isRecording = false

    fun attachSource(playback: SourcePlayback) {
        source = playback
    }

    fun start() {
        // 원본이 없는 세션(오프라인 복습, 현장 메모 연습)은 재생기를 기다리지 않는다.
        if (started) return
        if (playsSource && source == null) return
        started = true
        beginStage()
    }

    /**
     * 원본 재생이 거부됐다. 세션을 멈추지 않고 자막만 보는 방식으로 이어 간다.
     *
     * 듣기 단계는 들려줄 것이 없으므로 건너뛴다 — 빈 화면을 넘기게 하지 않기 위해서다.
     */
    fun onSourceRefused(reason: String) {
        if (!engine.playsSource) return
        playbackNotice = reason
        source?.pause()
        val wasChecking = isChecking
        isChecking = false
        // 듣기 단계에서 굳어 있었다면 엔진이 이번 걸음을 말하기부터 다시 짠다.
        engine.dropSourceAudio()
        if (wasChecking) advance() else beginStage()
    }

    private var playbackNotice: String? = null

    /**
     * 현재 단계를 시작한다.
     *
     * 세 단계 모두 **원본 재생으로 시작한다.** 2·3단계는 재생이 끝난 뒤 녹음으로 넘어간다 —
     * 들려주지 않고 따라 말하라고 하면 왕초보는 아무것도 못 한다(§7.2의 전제).
     */
    private fun beginStage() {
        val step = engine.currentStep ?: run {
            _uiState.value = snapshot().copy(finished = true)
            return
        }
        val sentence = plan.sentences[step.sentenceIndex]
        val segment = segmentFor(step.unit, sentence)

        isRecording = false
        isChecking = false
        _uiState.value = snapshot()

        if (!engine.playsBeforeStage) {
            // 들려줄 원본이 없거나, 외운 문장이라 먼저 듣지 않는다. 바로 말하기를 기다린다.
            speakNow(step.sentenceIndex)
            return
        }

        source?.playSegment(segment, playbackRate) { afterPlayback(step.sentenceIndex) }
    }

    /** 원본을 들려준 뒤. 듣기 단계면 다음 단계로, 말하기 단계면 말할 차례다. */
    private fun afterPlayback(sentenceIndex: Int) {
        if (!engine.currentStage.isSpeaking) advance() else speakNow(sentenceIndex)
    }

    /** 말할 차례. 무음 모드는 녹음하지 않고 사용자가 끝냈다고 알려 주길 기다린다. */
    private fun speakNow(sentenceIndex: Int) {
        if (engine.requiresRecording) startRecording(sentenceIndex) else waitForUser()
    }

    /** 녹음이 없는 단계에서 "말하기 끝" 버튼을 띄운다. */
    private fun waitForUser() {
        isRecording = true
        _uiState.value = snapshot()
    }

    /** `type=SINGLE`의 호흡 조각은 문장 전체가 아니라 그 조각만 재생한다 — §4.4. */
    private fun segmentFor(unit: PracticeUnit, sentence: Sentence) = when (unit) {
        PracticeUnit.FullSentence ->
            SegmentPadding.segmentFor(sentence, plan.transcript.timestampUnit)

        is PracticeUnit.Breath -> {
            val group = sentence.breathGroups.getOrNull(unit.groupIndex)
            if (group == null) {
                SegmentPadding.segmentFor(sentence, plan.transcript.timestampUnit)
            } else {
                SegmentPadding.segmentFor(
                    sentence.copy(startMs = group.startMs, endMs = group.endMs),
                    plan.transcript.timestampUnit,
                )
            }
        }
    }

    private fun startRecording(sentenceIndex: Int) {
        recorder.start(SentenceId.of(plan.id, sentenceIndex).value)
        isRecording = true
        _uiState.value = snapshot()
    }

    /**
     * §4.5 — 녹음 종료가 카운트 트리거다. 탭 한 번으로 이번 발화를 닫고 즉시 다음 회차로 넘어간다.
     */
    fun finishRecording() {
        val step = engine.currentStep ?: return
        // 무음 모드에서는 애초에 녹음을 시작하지 않았으므로 멈출 것도 없다.
        if (options.recordsVoice) {
            recorder.stop(SentenceId.of(plan.id, step.sentenceIndex).value)
        }
        isRecording = false

        // 외운 문장은 몇 회에 한 번, 말한 뒤에 원본을 들려준다 — 틀린 발음이 굳지 않게.
        if (engine.checksAfterSpeaking && playsSource) {
            val sentence = plan.sentences[step.sentenceIndex]
            isChecking = true
            _uiState.value = snapshot()
            source?.playSegment(segmentFor(step.unit, sentence), playbackRate) {
                isChecking = false
                advance()
            }
            return
        }
        advance()
    }

    private fun advance() {
        when (val event = engine.completeStage()) {
            is SessionEvent.SessionFinished -> _uiState.value = snapshot().copy(finished = true)
            else -> {
                if (event is SessionEvent.CountCompleted) {
                    onCountCompleted(event)
                }
                if (engine.isFinished) {
                    _uiState.value = snapshot().copy(finished = true)
                } else {
                    beginStage()
                }
            }
        }
    }

    /** L1 — 원본 구간을 들려준 뒤 방금 내 녹음을 이어서 재생한다. */
    fun playbackComparison() {
        val step = engine.currentStep ?: return
        val sentence = plan.sentences[step.sentenceIndex]
        val recording = latestRecordingFor(step.sentenceIndex) ?: return

        if (!playsSource) {
            recordingPlayer.play(recording) { beginStage() }
            return
        }
        source?.playSegment(segmentFor(step.unit, sentence), playbackRate) {
            recordingPlayer.play(recording) {
                // 비교가 끝나면 현재 단계를 처음부터 다시 듣는다.
                beginStage()
            }
        }
    }

    /**
     * 다시 듣기 — 현재 문장을 한 번 더 들려주고 **같은 단계를 처음부터** 이어 간다.
     *
     * 카운트는 올리지 않는다. 녹음 중이었다면 그 발화는 버린다 — 못 알아듣고 다시 들은
     * 회차를 완주로 치면 §7.2의 카운트가 부풀려진다.
     */
    fun replayCurrent() {
        val step = engine.currentStep ?: return
        if (!playsSource) return
        stopSpeakingWithoutCount(step.sentenceIndex)
        // 비교 재생 중이었다면 그 완료 콜백이 단계를 다시 시작하지 않도록 끊는다.
        recordingPlayer.release()
        // 외운 문장도 다시 듣기를 누르면 듣고 나서 말한다.
        // 재생기의 완료 콜백은 playSegment가 새로 덮어쓴다 — 이전 재생의 콜백은 오지 않는다.
        val sentence = plan.sentences[step.sentenceIndex]
        isRecording = false
        isChecking = false
        _uiState.value = snapshot()
        source?.playSegment(segmentFor(step.unit, sentence), playbackRate) {
            afterPlayback(step.sentenceIndex)
        }
    }

    /**
     * 다음 문장 — 지금 문장의 남은 회차를 건너뛴다. 말하던 중이었어도 카운트는 올리지 않는다.
     */
    fun skipSentence() {
        val step = engine.currentStep ?: return
        stopSpeakingWithoutCount(step.sentenceIndex)
        recordingPlayer.release()
        source?.pause()
        isChecking = false
        val next = engine.skipSentence()
        onSentenceSkipped(next)
        if (engine.isFinished) {
            _uiState.value = snapshot().copy(finished = true)
        } else {
            beginStage()
        }
    }

    /** 외웠어요 — 하던 걸음은 그대로 끝내고 다음 회차부터 바로 말하기만 한다. */
    fun markMemorized() {
        val index = engine.currentStep?.sentenceIndex ?: return
        engine.markMemorized(index)
        onMemorizedChanged(index, true)
        _uiState.value = snapshot()
    }

    /** 헷갈려요 — 외운 문장 표시를 풀고, 지금 바로 말하기 중이었다면 듣고 따라 하기로 다시 한다. */
    fun unmarkMemorized() {
        val index = engine.currentStep?.sentenceIndex ?: return
        stopSpeakingWithoutCount(index)
        recordingPlayer.release()
        val restarted = engine.unmarkMemorized(index)
        onMemorizedChanged(index, false)
        if (restarted) beginStage() else _uiState.value = snapshot()
    }

    /** 말하는 중이었다면 녹음을 닫는다. 카운트는 올리지 않는다. */
    private fun stopSpeakingWithoutCount(sentenceIndex: Int) {
        if (isRecording && options.recordsVoice) {
            recorder.stop(SentenceId.of(plan.id, sentenceIndex).value)
        }
        isRecording = false
    }

    private fun latestRecordingFor(sentenceIndex: Int): File? =
        recorder.latestRecording(SentenceId.of(plan.id, sentenceIndex).value)

    fun release() {
        recorder.stop()
        recordingPlayer.release()
        source?.release()
        source = null
    }

    private fun snapshot(): SessionUiState {
        val step = engine.currentStep
        val sentence = step?.let { plan.sentences.getOrNull(it.sentenceIndex) }
        return SessionUiState(
            stage = engine.currentStage,
            sentenceText = sentence?.text.orEmpty(),
            translationKo = sentence?.translationKo.orEmpty(),
            transliterationKo = sentence?.transliterationKo,
            showSubtitle = engine.showsSubtitle,
            recording = isRecording,
            hasRecording = step != null && latestRecordingFor(step.sentenceIndex) != null,
            completedCounts = engine.completedCounts,
            targetCounts = engine.targetCounts,
            remainingCounts = engine.remainingCounts,
            finished = engine.isFinished,
            currentRep = engine.currentRep,
            totalReps = engine.totalReps,
            currentSentenceNumber = engine.currentSentenceNumber,
            sentenceCount = engine.sentenceCount,
            dailyGoalMet = engine.dailyGoalMet,
            remainingDailySec = engine.remainingDailySec,
            playbackNotice = playbackNotice,
            playsSource = playsSource,
            stageNumber = engine.stageNumber,
            stageCount = engine.stagesPerCount,
            memorized = engine.isCurrentSentenceMemorized,
            checking = isChecking,
            overallCompletedCounts = engine.overallCompletedCounts,
            memorizedCount = plan.sentences.indices.count { it in engine.memorizedSentences },
        )
    }
}
