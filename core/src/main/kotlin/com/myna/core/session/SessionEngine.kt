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
    private val options: SessionOptions = SessionOptions(),
    /** 오늘 이미 쌓은 발화 시간. 세션 중에 목표 달성을 알리기 위해 받는다. */
    private val dailyAlreadySec: Int = 0,
    /** 오늘의 목표. 0이면 달성 판정을 하지 않는다. */
    private val dailyTargetSec: Int = 0,
    private val steps: List<SessionStep> = SessionPlan.expand(plan),
) {
    private var stepCursor: Int = 0
    private var stageIndex: Int = 0

    /** 1카운트를 구성하는 단계 수. 원본을 들을 수 없으면 듣기가 빠져 하나 줄어든다. */
    public val stagesPerCount: Int = options.stages.size

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

    public val currentStage: ShadowingStage get() = options.stages[stageIndex]

    public val currentSentence: Sentence?
        get() = currentStep?.let { plan.sentences.getOrNull(it.sentenceIndex) }

    /** §4.5 — 세그먼트 링 진행바가 쓰는 값. */
    public val targetCounts: Int = steps.count { it.countsTowardTarget }

    /** 이 영상을 몇 번 반복하는가. 화면에 보여 줄 숫자는 카운트가 아니라 이쪽이다. */
    public val totalReps: Int = plan.targetReps.coerceAtLeast(1)

    public val sentenceCount: Int = plan.sentences.size

    /** 지금 몇 회차인가 (1부터). */
    public val currentRep: Int get() = (currentStep?.repIndex ?: 0) + 1

    /** 이번 회차에서 몇 번째 문장인가 (1부터). */
    public val currentSentenceNumber: Int get() = (currentStep?.sentenceIndex ?: 0) + 1

    /**
     * 오늘 목표를 채웠는가.
     *
     * §4.2.1 — 예산은 상한이 아니라 목표값이다. 그래서 세션을 강제로 끝내지 않고
     * **끝낼 수 있다고 알리기만 한다.** 더 하고 싶으면 그대로 두면 된다.
     *
     * 이 판정이 필요한 이유는 반복 횟수가 커질 수 있기 때문이다. 문장 17개짜리 영상에
     * 18회 반복이면 카운트가 306이 되고, 한 카운트가 3단계이므로 900번 넘는 상호작용이
     * 된다. 목표를 채운 지점을 알려 주지 않으면 사용자는 언제 멈춰야 할지 알 수 없다.
     */
    public val dailyGoalMet: Boolean
        get() = dailyTargetSec > 0 && dailyAlreadySec + achievedSec >= dailyTargetSec

    /** 오늘 목표까지 남은 발화 시간(초). 목표를 이미 넘겼으면 0. */
    public val remainingDailySec: Int
        get() = (dailyTargetSec - dailyAlreadySec - achievedSec).coerceAtLeast(0)

    public val remainingCounts: Int get() = (targetCounts - completedCounts).coerceAtLeast(0)

    /** §7.2 — 자막을 보여줄지. 마지막 말하기 단계에서만 숨긴다. */
    public val showsSubtitle: Boolean get() = currentStage.showsSubtitle

    /** 무음 모드에서는 어느 단계에서도 마이크를 켜지 않는다. */
    public val requiresRecording: Boolean get() = options.requiresRecording(currentStage)

    /** 이 단계에서 원본을 재생해야 하는가. 들을 수 없는 자리에서는 아무것도 재생하지 않는다. */
    public val playsSource: Boolean get() = options.sourceAudioAvailable

    /**
     * 현재 단계를 끝낸다. 듣기 단계는 재생이 끝났을 때, 말하기 단계는 녹음이 끝났을 때 부른다.
     */
    public fun completeStage(): SessionEvent {
        val step = currentStep ?: return SessionEvent.SessionFinished

        if (stageIndex + 1 < options.stages.size) {
            stageIndex += 1
            return SessionEvent.StageAdvanced(currentStage)
        }

        // 모든 단계 완주 — 한 걸음이 끝났다.
        stageIndex = 0
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
