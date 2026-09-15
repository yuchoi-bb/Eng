package com.eng.shadowing.core.budget

import kotlin.math.roundToInt

/**
 * 일일 예산 적응. REQUIREMENTS §4.7.
 *
 * 완료율은 **시간 기준으로만** 계산한다. 수동으로 크게 잡은 횟수는 사용자 의도이므로
 * 자동 조정의 입력이 아니다 — §4.2.1.
 */
public object BudgetAdaptation {

    /** §4.7 — 콜드스타트 구간. 3일차부터 조정한다. */
    public const val WARMUP_DAYS: Int = 2

    public const val INCREASE_THRESHOLD: Double = 1.0
    public const val DECREASE_THRESHOLD: Double = 0.6

    public const val INCREASE_RATE: Double = 0.10
    public const val DECREASE_RATE: Double = -0.20

    /** §4.7 — 일일 변화폭 클램프. */
    public const val MAX_DAILY_CHANGE: Double = 0.20

    /**
     * 예산의 절대 하한/상한.
     *
     * **§4.7에 명시가 없어 제안 기본값으로 둔다.** 없으면 실제로 망가진다:
     * −20%가 20일 연속이면 `300 × 0.8^20 ≈ 3초`가 되어 앱이 무의미해지고,
     * +10%가 60일 연속이면 `300 × 1.1^60 ≈ 25시간/일`이 되어 달성이 불가능해진다.
     * 하한 60초는 "그래도 하루 한 문장은 말한다", 상한 1800초는 §8이 10분을 초보자
     * 좌절 구간으로 본 것을 고려한 여유값이다.
     */
    public const val MIN_BUDGET_SEC: Int = 60
    public const val MAX_BUDGET_SEC: Int = 1800

    /**
     * 완료율. 시간 기준이며 **상한이 없다** — §4.2.1에 따라 예산은 상한이 아니라 목표값이고,
     * 초과 달성은 차단하지 않고 120% 같은 값으로 표시한다.
     */
    public fun completionRate(achievedSec: Int, dailyTargetSec: Int): Double =
        if (dailyTargetSec <= 0) 0.0 else achievedSec.toDouble() / dailyTargetSec

    /**
     * 다음 날 예산을 산출한다.
     *
     * @param daysLogged 지금까지 기록된 학습 일수. [WARMUP_DAYS] 이하면 조정하지 않는다.
     */
    public fun nextBudgetSec(
        currentBudgetSec: Int,
        completionRate: Double,
        daysLogged: Int,
    ): Int {
        if (daysLogged <= WARMUP_DAYS) return currentBudgetSec

        val rawChange = when {
            completionRate >= INCREASE_THRESHOLD -> INCREASE_RATE
            completionRate < DECREASE_THRESHOLD -> DECREASE_RATE
            else -> 0.0
        }
        val change = rawChange.coerceIn(-MAX_DAILY_CHANGE, MAX_DAILY_CHANGE)
        val next = (currentBudgetSec * (1 + change)).roundToInt()
        return next.coerceIn(MIN_BUDGET_SEC, MAX_BUDGET_SEC)
    }
}
