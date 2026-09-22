package com.myna.core.budget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** REQUIREMENTS §4.7 콜드스타트 & 적응. */
class BudgetAdaptationTest {

    @Test
    fun `1일차와 2일차에는 조정하지 않는다`() {
        // §4.7 — 개인화 판단 없이 시작하고 3일차부터 적응한다.
        assertEquals(300, BudgetAdaptation.nextBudgetSec(300, completionRate = 0.1, daysLogged = 1))
        assertEquals(300, BudgetAdaptation.nextBudgetSec(300, completionRate = 2.0, daysLogged = 2))
    }

    @Test
    fun `완료율 100퍼센트 이상이면 예산을 10퍼센트 올린다`() {
        assertEquals(330, BudgetAdaptation.nextBudgetSec(300, completionRate = 1.0, daysLogged = 3))
        assertEquals(330, BudgetAdaptation.nextBudgetSec(300, completionRate = 1.8, daysLogged = 3))
    }

    @Test
    fun `완료율 60퍼센트 미만이면 예산을 20퍼센트 내린다`() {
        assertEquals(240, BudgetAdaptation.nextBudgetSec(300, completionRate = 0.59, daysLogged = 3))
    }

    @Test
    fun `완료율이 60과 100 사이면 그대로 둔다`() {
        assertEquals(300, BudgetAdaptation.nextBudgetSec(300, completionRate = 0.6, daysLogged = 3))
        assertEquals(300, BudgetAdaptation.nextBudgetSec(300, completionRate = 0.99, daysLogged = 3))
    }

    @Test
    fun `예산은 하한 아래로 내려가지 않는다`() {
        // 명세에 절대 하한이 없어 제안 기본값을 뒀다. 없으면 연속 실패 시 0으로 수렴한다.
        var budget = 300
        repeat(40) { budget = BudgetAdaptation.nextBudgetSec(budget, completionRate = 0.0, daysLogged = 10) }
        assertEquals(BudgetAdaptation.MIN_BUDGET_SEC, budget)
    }

    @Test
    fun `예산은 상한 위로 올라가지 않는다`() {
        var budget = 300
        repeat(200) { budget = BudgetAdaptation.nextBudgetSec(budget, completionRate = 1.5, daysLogged = 10) }
        assertEquals(BudgetAdaptation.MAX_BUDGET_SEC, budget)
    }

    @Test
    fun `완료율에는 상한이 없다`() {
        // §4.2.1 — 예산은 상한이 아니라 목표값이다. 초과 달성은 120퍼센트로 표시한다.
        assertEquals(1.2, BudgetAdaptation.completionRate(360, 300))
        assertTrue(BudgetAdaptation.completionRate(900, 300) > 1.0)
    }

    @Test
    fun `목표가 0이면 완료율은 0이다`() {
        assertEquals(0.0, BudgetAdaptation.completionRate(100, 0))
    }
}
