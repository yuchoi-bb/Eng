package com.myna.core.session

import com.myna.core.model.PlanPosition
import com.myna.core.model.Sentence
import com.myna.core.model.VideoPlan

/** 한 단계를 끝냈을 때 세션이 내놓는 결과. */
public sealed interface SessionEvent {
    /** 같은 걸음 안에서 다음 단계로 넘어갔다. */
    public data class StageAdvanced(val stage: ShadowingStage) : SessionEvent

    /** 한 걸음의 모든 단계를 완주했다. §7.2 — 여기서만 카운트가 오른다. */
    public data class CountCompleted(
        val sentenceIndex: Int,
        val totalCounts: Int,
        val achievedSecDelta: Int,
        /** 다음에 이어서 할 자리. 마지막 걸음이었으면 null. */
        val nextPosition: PlanPosition?,
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
    /** 외운 문장의 번호. 기본은 계획에 적힌 값이다. */
    memorized: Set<Int> = plan.memorizedSentences,
    /** 이어서 할 자리. null이거나 계획 밖이면 처음부터 한다. */
    startAt: PlanPosition? = null,
    /** 이 문장들만 연습한다. 오프라인 복습에서 들어 본 문장만 남길 때 쓴다. null이면 전부. */
    sentenceFilter: Set<Int>? = null,
    private val steps: List<SessionStep> = SessionPlan.expand(plan)
        .filter { sentenceFilter == null || it.sentenceIndex in sentenceFilter },
) {
    private val memorized: MutableSet<Int> = memorized.toMutableSet()

    /** 원본을 들을 수 있는가. 세션 도중 재생이 막히면 꺼진다([dropSourceAudio]). */
    private var sourceAudio: Boolean = options.sourceAudioAvailable

    private var stepCursor: Int = firstStepAt(startAt)
    private var stageIndex: Int = 0

    /**
     * 지금 걸음의 단계. **걸음을 시작할 때 정해 둔다** — 도중에 "외웠어요"를 눌러도 하던
     * 걸음은 그대로 끝내고, 다음 회차부터 바뀐다.
     */
    private var currentStages: List<ShadowingStage> = stagesForCurrentStep()

    init {
        skipMemorizedBreathSteps()
    }

    /** 지금 걸음을 구성하는 단계 수. 회차와 외운 문장 여부에 따라 1~3개다. */
    public val stagesPerCount: Int get() = currentStages.size

    /** 지금 단계가 이번 걸음의 몇 번째인가 (1부터). */
    public val stageNumber: Int get() = stageIndex + 1

    /** 이 세션에서 올린 카운트. 오늘 로그에 더할 값이다. */
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

    public val currentStage: ShadowingStage
        get() = currentStages.getOrElse(stageIndex) { currentStages.last() }

    /** 다음에 이어서 할 자리. 모든 걸음을 끝냈으면 null. */
    public val position: PlanPosition?
        get() = currentStep?.let { PlanPosition(it.sentenceIndex, it.repIndex) }

    /** 지금 문장을 외웠다고 표시했는가. */
    public val isCurrentSentenceMemorized: Boolean
        get() = currentStep?.sentenceIndex?.let { it in memorized } ?: false

    public val memorizedSentences: Set<Int> get() = memorized

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

    /**
     * 이 영상에서 지금까지 끝낸 카운트 — 이어서 하기로 건너뛴 앞부분까지 포함한다.
     * 진행바는 이 세션이 아니라 영상 전체를 기준으로 보여 준다.
     */
    public val overallCompletedCounts: Int
        get() = steps.subList(0, stepCursor.coerceAtMost(steps.size)).count { it.countsTowardTarget }

    public val remainingCounts: Int get() = (targetCounts - overallCompletedCounts).coerceAtLeast(0)

    /** §7.2 — 자막을 보여줄지. 마지막 말하기 단계에서만 숨긴다. */
    public val showsSubtitle: Boolean get() = currentStage.showsSubtitle

    /** 무음 모드에서는 어느 단계에서도 마이크를 켜지 않는다. */
    public val requiresRecording: Boolean get() = options.requiresRecording(currentStage)

    /** 이 세션에서 원본을 재생할 수 있는가. 들을 수 없는 자리에서는 아무것도 재생하지 않는다. */
    public val playsSource: Boolean get() = sourceAudio

    /**
     * 이 단계를 시작할 때 원본을 먼저 들려주는가.
     *
     * 외운 문장은 먼저 듣지 않는다 — 기억에서 꺼내 말하는 것이 이 단계의 목적이다.
     */
    public val playsBeforeStage: Boolean
        get() = sourceAudio && currentStage != ShadowingStage.RECALL

    /**
     * 말한 **뒤에** 원본을 들려줄 차례인가. 외운 문장만 해당하고,
     * [SessionOptions.RECALL_CHECK_INTERVAL] 회차마다 한 번이다.
     */
    public val checksAfterSpeaking: Boolean
        get() {
            val step = currentStep ?: return false
            return sourceAudio &&
                currentStage == ShadowingStage.RECALL &&
                (step.repIndex + 1) % SessionOptions.RECALL_CHECK_INTERVAL == 0
        }

    /**
     * 지금 문장을 외웠다고 표시한다. **하던 걸음은 그대로 끝내고** 다음 회차부터 바로 말하기만 한다.
     */
    public fun markMemorized(sentenceIndex: Int) {
        memorized += sentenceIndex
    }

    /**
     * 외운 문장 표시를 푼다. 지금 그 문장을 바로 말하기로 하고 있었다면 **이번 걸음을
     * 처음부터** 듣고 따라 하는 방식으로 다시 한다 — 막혔으니 푼 것이다.
     *
     * @return 지금 걸음을 다시 시작했으면 true.
     */
    public fun unmarkMemorized(sentenceIndex: Int): Boolean {
        memorized -= sentenceIndex
        val restart = currentStep?.sentenceIndex == sentenceIndex &&
            currentStage == ShadowingStage.RECALL
        if (restart) restartStep()
        return restart
    }

    /**
     * 원본 재생이 막혔다. 이후 걸음은 듣기 단계 없이 구성하고, 지금 듣기 단계였다면
     * 이번 걸음을 말하기부터 다시 한다.
     */
    public fun dropSourceAudio() {
        if (!sourceAudio) return
        sourceAudio = false
        if (currentStage == ShadowingStage.LISTEN) restartStep()
    }

    /**
     * 지금 문장의 남은 회차를 건너뛰고 **다음 문장의 1회차**로 간다.
     *
     * 너무 쉽거나 따라 할 필요 없는 문장에서 반복을 다 채우라고 하면 앱을 닫게 된다.
     * 건너뛴 회차는 카운트도 발화 시간도 올리지 않는다 — 말하지 않았으니까.
     *
     * @return 다음 자리. 마지막 문장이었으면 null이고 세션이 끝난다.
     */
    public fun skipSentence(): PlanPosition? {
        val current = currentStep ?: return null
        // 걸음은 문장별로 붙어 있다(SessionPlan.expand). 같은 문장이 끝나는 곳까지 넘긴다.
        var next = stepCursor
        while (next < steps.size && steps[next].sentenceIndex == current.sentenceIndex) next += 1
        stepCursor = next
        restartStep()
        skipMemorizedBreathSteps()
        return position
    }

    private fun restartStep() {
        stageIndex = 0
        currentStages = stagesForCurrentStep()
    }

    private fun stagesForCurrentStep(): List<ShadowingStage> {
        val step = currentStep ?: return options.stages
        // 호흡 조각은 긴 문장을 입에 얹는 발판이다. 외운 문장 규칙을 적용하지 않는다.
        val memorizedNow = step.countsTowardTarget && step.sentenceIndex in memorized
        return options.stagesFor(step.repIndex, memorizedNow, sourceAudio)
    }

    /** 외운 문장의 호흡 조각은 건너뛴다. 이미 입에 붙은 문장이다. */
    private fun skipMemorizedBreathSteps() {
        var moved = false
        while (true) {
            val step = currentStep ?: break
            if (step.countsTowardTarget || step.sentenceIndex !in memorized) break
            stepCursor += 1
            moved = true
        }
        if (moved) restartStep()
    }

    /**
     * [position]이 가리키는 걸음. (문장, 회차) 순서로 그 자리이거나 그 뒤의 첫 걸음이다.
     * 계획 밖(반복 횟수를 줄였거나 문장이 바뀐 경우)이면 처음부터 한다.
     */
    private fun firstStepAt(position: PlanPosition?): Int {
        if (position == null) return 0
        val index = steps.indexOfFirst { step ->
            step.sentenceIndex > position.sentenceIndex ||
                (step.sentenceIndex == position.sentenceIndex && step.repIndex >= position.repIndex)
        }
        return if (index < 0) 0 else index
    }

    /**
     * 현재 단계를 끝낸다. 듣기 단계는 재생이 끝났을 때, 말하기 단계는 녹음이 끝났을 때 부른다.
     */
    public fun completeStage(): SessionEvent {
        val step = currentStep ?: return SessionEvent.SessionFinished

        if (stageIndex + 1 < currentStages.size) {
            stageIndex += 1
            return SessionEvent.StageAdvanced(currentStage)
        }

        // 모든 단계 완주 — 한 걸음이 끝났다.
        stepCursor += 1
        restartStep()
        skipMemorizedBreathSteps()

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
            nextPosition = position,
        )
    }
}
