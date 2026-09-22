package com.myna.core.session

import com.myna.core.model.Sentence
import com.myna.core.model.VideoPlan

/** 한 단계를 끝냈을 때 세션이 내놓는 결과. */
public sealed interface SessionEvent {
    /** 같은 걸음 안에서 다음 단계로 넘어갔다. */
    public data class StageAdvanced(val stage: ShadowingStage) : SessionEvent

    /** 3단계를 완주했다. §7.2 — 여기서만 카운트가 오른다. */
    public data class CountCompleted(
        val sentenceIndex: Int,
        val totalCounts: Int,
        val achievedSecDelta: Int,
    ) : SessionEvent

    /** 호흡 조각을 끝냈다. 카운트는 오르지 않는다. */
    public data object BreathGroupCompleted : SessionEvent

    public data object SessionFinished : SessionEvent
}

/**
 * 반복 세션의 진행 상태 기계. REQUIREMENTS §4.3 / §4.5 / §7.2.
 *
 * 카운트 트리거는 **녹음 종료 → 자동 +1 → 즉시 다음 회차**다(§4.5). 탭이 필요 없는
 * 무한 루프이므로, UI는 녹음이 끝날 때마다 [completeStage]만 부르면 된다.
 */
public class SessionEngine(
    private val plan: VideoPlan,
    private val steps: List<SessionStep> = SessionPlan.expand(plan),
) {
    private var stepCursor: Int = 0
    private var stage: ShadowingStage = ShadowingStage.FIRST

    public var completedCounts: Int = 0
        private set

    /**
     * 이 세션에서 쌓은 발화 시간(초). 일일 예산에 누적된다 — §4.2.1.
     *
     * **녹음 실측 길이가 아니라 문장의 기준 길이를 더한다.** §4.2의
     * `suggestedReps = ceil(dailyTargetSec / videoSpeechSec)`는 "1회차 = videoSpeechSec만큼
     * 말한다"를 전제로 세워진 식이다. 2·3단계 녹음을 각각 더하면 1회차가 두 배로 잡혀
     * 제시 횟수를 그대로 따랐을 때 목표를 2배 초과하게 된다. 기준 길이를 쓰면 두 절이
     * 맞아떨어지고, 중간에 뜸을 들여도 수치가 부풀지 않는다.
     */
    public var achievedSec: Int = 0
        private set

    public val isFinished: Boolean get() = stepCursor >= steps.size

    public val currentStep: SessionStep? get() = steps.getOrNull(stepCursor)

    public val currentStage: ShadowingStage get() = stage

    public val currentSentence: Sentence?
        get() = currentStep?.let { plan.sentences.getOrNull(it.sentenceIndex) }

    /** §4.5 — 세그먼트 링 진행바가 쓰는 값. */
    public val targetCounts: Int = steps.count { it.countsTowardTarget }

    public val remainingCounts: Int get() = (targetCounts - completedCounts).coerceAtLeast(0)

    /** §7.2 — 자막을 보여줄지. 3단계에서만 숨긴다. */
    public val showsSubtitle: Boolean get() = stage.showsSubtitle

    public val requiresRecording: Boolean get() = stage.requiresRecording

    /**
     * 현재 단계를 끝낸다. 듣기 단계는 재생이 끝났을 때, 말하기 단계는 녹음이 끝났을 때 부른다.
     */
    public fun completeStage(): SessionEvent {
        val step = currentStep ?: return SessionEvent.SessionFinished

        val nextStage = stage.next()
        if (nextStage != null) {
            stage = nextStage
            return SessionEvent.StageAdvanced(nextStage)
        }

        // 3단계 완주 — 한 걸음이 끝났다.
        stage = ShadowingStage.FIRST
        stepCursor += 1

        if (!step.countsTowardTarget) {
            return SessionEvent.BreathGroupCompleted
        }

        completedCounts += 1
        val delta = plan.sentences.getOrNull(step.sentenceIndex)?.let { it.durationMs / 1000 } ?: 0
        achievedSec += delta

        return SessionEvent.CountCompleted(
            sentenceIndex = step.sentenceIndex,
            totalCounts = completedCounts,
            achievedSecDelta = delta,
        )
    }
}
