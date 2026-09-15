package com.eng.shadowing.core.session

import com.eng.shadowing.core.model.VideoPlan
import com.eng.shadowing.core.model.VideoType

/** 한 번의 연습 단위. */
public sealed interface PracticeUnit {
    /** 문장 전체. 이것만 카운트로 인정된다 — §4.3. */
    public data object FullSentence : PracticeUnit

    /** 호흡 단위 조각. `type=SINGLE`의 부분 반복용 — §4.4. */
    public data class Breath(val groupIndex: Int) : PracticeUnit
}

/**
 * 세션의 한 걸음. 3단계(듣기 → 보고 말하기 → 자막 끄고 말하기)를 거쳐야 끝난다.
 */
public data class SessionStep(
    val sentenceIndex: Int,
    val repIndex: Int,
    val unit: PracticeUnit,
    /** §4.3 — 카운트 단위는 문장 1개다. 호흡 조각은 카운트에 포함되지 않는다. */
    val countsTowardTarget: Boolean,
)

/**
 * 영상 유형별 반복 순서를 단계 목록으로 펼친다. REQUIREMENTS §4.4.
 *
 * 순서 정책을 UI가 아니라 여기에 두는 이유는 단순하다 — 테스트할 수 있어야 하기 때문이다.
 */
public object SessionPlan {

    public fun expand(plan: VideoPlan): List<SessionStep> {
        val reps = plan.targetReps.coerceAtLeast(1)
        return buildList {
            plan.sentences.forEachIndexed { sentenceIndex, sentence ->
                repeat(reps) { repIndex ->
                    // §4.4 SINGLE — "호흡 단위로 분할 → 부분 반복 후 전체 통과".
                    //
                    // 호흡 조각은 **각 문장의 첫 회차에만** 넣는다. 명세는 조각 반복의
                    // 주기를 적지 않았지만, 매 회차마다 넣으면 38회 반복 영상에서
                    // 조각 녹음만 100회를 넘긴다. 조각은 긴 문장을 입에 얹기 위한
                    // 발판이므로 한 번 거치면 목적을 달성한다.
                    if (plan.transcript.type == VideoType.SINGLE && repIndex == 0) {
                        sentence.breathGroups.indices.forEach { groupIndex ->
                            add(
                                SessionStep(
                                    sentenceIndex = sentenceIndex,
                                    repIndex = repIndex,
                                    unit = PracticeUnit.Breath(groupIndex),
                                    countsTowardTarget = false,
                                ),
                            )
                        }
                    }
                    add(
                        SessionStep(
                            sentenceIndex = sentenceIndex,
                            repIndex = repIndex,
                            unit = PracticeUnit.FullSentence,
                            countsTowardTarget = true,
                        ),
                    )
                }
            }
        }
    }
}
