package com.myna.core.offline

import com.myna.core.model.SentenceId
import com.myna.core.model.VideoPlan

/** 회선 없이 이 영상을 연습할 수 있는가. */
public enum class OfflineAvailability {
    /** 모든 문장을 한 번 이상 들어 봤다. 그대로 복습할 수 있다. */
    READY,

    /** 일부만 들어 봤다. 들어 본 문장만 골라 연습한다. */
    PARTIAL,

    /** 한 번도 들어 본 적이 없다. 오프라인 연습이 의미 없다. */
    NOT_READY,
}

public data class OfflinePlanStatus(
    val totalSentences: Int,
    val practicableSentences: Int,
    val availability: OfflineAvailability,
)

/**
 * 오프라인에서 무엇을 연습할 수 있는지 판단한다.
 *
 * 문장과 번역은 기기에 이미 있으므로 회선과 무관하다. 회선이 없을 때 사라지는 것은
 * **원본 오디오**뿐이다(유튜브는 임베드 재생이라 회선이 필수다).
 *
 * 그래서 기준은 저장 여부가 아니라 **이미 들어 봤는가**다. 한 번도 못 들어 본 문장을
 * 자막만 보고 따라 하는 것은 쉐도잉이 아니라 그냥 읽기다. 반대로 한 번이라도 완주한
 * 문장은 소리가 귀에 남아 있으므로, 원본 없이도 복습이 성립한다.
 */
public object OfflineReadiness {

    /**
     * @param clearedSentenceIds 한 번 이상 완주한 문장. [com.myna.core.model.SentenceId] 값.
     */
    public fun statusFor(plan: VideoPlan, clearedSentenceIds: Set<String>): OfflinePlanStatus {
        val total = plan.sentences.size
        val practicable = plan.sentences.count { sentence ->
            SentenceId.of(plan.id, sentence.index).value in clearedSentenceIds
        }
        return OfflinePlanStatus(
            totalSentences = total,
            practicableSentences = practicable,
            availability = when {
                total == 0 || practicable == 0 -> OfflineAvailability.NOT_READY
                practicable == total -> OfflineAvailability.READY
                else -> OfflineAvailability.PARTIAL
            },
        )
    }

    /**
     * 오프라인 세션에 쓸 계획. 들어 본 적 없는 문장은 덜어 낸다.
     *
     * 문장을 덜어 내면 index가 어긋나므로 **다시 매긴다.** 진행도와 복습 큐는
     * [SentenceId]로 문장을 찾는데, 그 키가 index를 담고 있기 때문이다.
     * 원본 계획은 그대로 두고 이 사본만 세션에 넘긴다.
     */
    public fun practicablePlan(plan: VideoPlan, clearedSentenceIds: Set<String>): VideoPlan? {
        val kept = plan.sentences.filter { sentence ->
            SentenceId.of(plan.id, sentence.index).value in clearedSentenceIds
        }
        if (kept.isEmpty()) return null
        if (kept.size == plan.sentences.size) return plan
        return plan.copy(
            transcript = plan.transcript.copy(
                sentences = kept.mapIndexed { position, sentence -> sentence.copy(index = position) },
            ),
        )
    }
}
