package com.myna.ui.session

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.myna.core.model.Sentence
import com.myna.core.model.SentenceId
import com.myna.core.model.VideoPlan
import com.myna.core.playback.SegmentPadding
import com.myna.core.session.PracticeUnit
import com.myna.core.session.SessionEngine
import com.myna.core.session.SessionEvent
import com.myna.core.session.ShadowingStage
import com.myna.media.RecordingPlayer
import com.myna.media.SentenceRecorder
import com.myna.media.SourcePlayback
import java.io.File

internal data class SessionUiState(
    val stage: ShadowingStage = ShadowingStage.FIRST,
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
) {
    val progressFraction: Float
        get() = if (targetCounts == 0) 0f else completedCounts.toFloat() / targetCounts
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
) {
    private val recorder = SentenceRecorder(context)
    private val recordingPlayer = RecordingPlayer()
    private val engine = SessionEngine(plan)

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
        if (started || source == null) return
        started = true
        beginStage()
    }

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
        _uiState.value = snapshot()

        source?.playSegment(segment, playbackRate) {
            if (engine.requiresRecording) {
                startRecording(step.sentenceIndex)
            } else {
                // 1단계 듣기만 — 재생이 끝나면 바로 다음 단계로.
                advance()
            }
        }
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
        recorder.stop(SentenceId.of(plan.id, step.sentenceIndex).value)
        isRecording = false
        advance()
    }

    private fun advance() {
        when (engine.completeStage()) {
            is SessionEvent.SessionFinished -> _uiState.value = snapshot().copy(finished = true)
            else -> if (engine.isFinished) {
                _uiState.value = snapshot().copy(finished = true)
            } else {
                beginStage()
            }
        }
    }

    /** L1 — 원본 구간을 들려준 뒤 방금 내 녹음을 이어서 재생한다. */
    fun playbackComparison() {
        val step = engine.currentStep ?: return
        val sentence = plan.sentences[step.sentenceIndex]
        val recording = latestRecordingFor(step.sentenceIndex) ?: return

        source?.playSegment(segmentFor(step.unit, sentence), playbackRate) {
            recordingPlayer.play(recording) {
                // 비교가 끝나면 현재 단계를 처음부터 다시 듣는다.
                beginStage()
            }
        }
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
        )
    }
}
