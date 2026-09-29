package com.myna.core.plan

import com.myna.core.model.PlanPosition
import com.myna.core.model.SentenceId
import com.myna.core.model.Transcript
import com.myna.core.model.VideoPlan
import com.myna.core.session.RepsCalculator

/**
 * 저장된 영상의 문장을 고쳤을 때 진행 상태를 새 문장 번호로 옮긴다.
 *
 * 문장을 지우거나 시각을 고치면 번호가 바뀐다. 외운 문장 표시와 이어서 하기 자리는 번호로
 * 적혀 있으므로, 그대로 두면 엉뚱한 문장이 "외움"이 되고 엉뚱한 자리에서 이어진다.
 */
public object PlanEdit {

    /**
     * @param originOfNew 새 문장 i가 원래 몇 번 문장이었는가. 새로 추가한 문장은 null.
     */
    public fun rebase(
        old: VideoPlan,
        transcript: Transcript,
        originOfNew: List<Int?>,
        dailyTargetSec: Int,
    ): VideoPlan {
        val suggested = RepsCalculator.suggestedReps(dailyTargetSec, transcript)
        return old.copy(
            transcript = transcript,
            suggestedReps = suggested,
            // 자동 모드면 새 발화 길이에 맞춰 다시 계산한다. 수동 횟수는 사용자 의도라 건드리지 않는다.
            targetReps = if (old.autoReps) suggested else old.targetReps,
            memorizedSentences = originOfNew.indices
                .filter { originOfNew[it]?.let { origin -> origin in old.memorizedSentences } == true }
                .toSet(),
            resumeAt = old.resumeAt?.let { resumeAt(it, originOfNew) },
        )
    }

    /**
     * 이어서 할 자리를 옮긴다. 그 문장이 남아 있으면 같은 회차에서, 지워졌으면 **그 뒤에 남은
     * 첫 문장의 1회차**에서 잇는다. 뒤에 남은 문장이 없으면 처음부터다.
     */
    private fun resumeAt(old: PlanPosition, originOfNew: List<Int?>): PlanPosition? {
        val next = originOfNew.withIndex()
            .filter { (_, origin) -> origin != null && origin >= old.sentenceIndex }
            .minByOrNull { (_, origin) -> origin!! }
            ?: return null
        val sameSentence = next.value == old.sentenceIndex
        return PlanPosition(next.index, if (sameSentence) old.repIndex else 0)
    }

    /**
     * "한 번 이상 완주한 문장" 기록을 새 번호로 옮긴다. 오프라인 복습 대상이 이것으로 정해진다.
     */
    public fun remapClearedIds(
        clearedIds: Set<String>,
        plan: VideoPlan,
        originOfNew: List<Int?>,
    ): Set<String> {
        val prefix = SentenceId.of(plan.id, 0).value.removeSuffix("0")
        val ofThisPlan = Regex("^" + Regex.escape(prefix) + "\\d+$")
        val others = clearedIds.filterNot { ofThisPlan.matches(it) }
        val kept = originOfNew.indices.filter { index ->
            val origin = originOfNew[index] ?: return@filter false
            SentenceId.of(plan.id, origin).value in clearedIds
        }
        return others.toSet() + kept.map { SentenceId.of(plan.id, it).value }
    }
}
