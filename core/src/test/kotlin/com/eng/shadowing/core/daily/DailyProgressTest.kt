package com.eng.shadowing.core.daily

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** REQUIREMENTS §4.6 날짜 처리 + F-5 스트릭. */
class DailyProgressTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 15)

    private fun logs(vararg entries: Pair<LocalDate, Int>): Map<LocalDate, DailySpeechLog> =
        entries.associate { (date, achieved) ->
            date to DailySpeechLog(date = date, dailyTargetSec = 300, achievedSec = achieved)
        }

    @Test
    fun `오늘 로그가 없으면 현재 목표로 새로 만든다`() {
        // §4.6 — 자정 롤오버 작업 없이 조회 시점에 판정한다.
        val log = DailyProgress.logFor(emptyMap(), today, currentTargetSec = 600)
        assertEquals(today, log.date)
        assertEquals(600, log.dailyTargetSec)
        assertEquals(0, log.achievedSec)
    }

    @Test
    fun `목표를 채운 날이 연속이면 스트릭이 는다`() {
        val map = logs(
            today to 300,
            today.minusDays(1) to 320,
            today.minusDays(2) to 300,
        )
        assertEquals(3, DailyProgress.currentStreak(map, today))
    }

    @Test
    fun `오늘을 아직 못 채워도 어제까지 이어졌으면 스트릭은 살아 있다`() {
        // 매일 아침 0으로 보이면 이탈을 부른다.
        val map = logs(
            today to 10,
            today.minusDays(1) to 300,
            today.minusDays(2) to 300,
        )
        assertEquals(2, DailyProgress.currentStreak(map, today))
    }

    @Test
    fun `하루라도 비면 스트릭이 끊긴다`() {
        val map = logs(
            today to 300,
            today.minusDays(1) to 300,
            today.minusDays(3) to 300, // 이틀 전이 비었다
        )
        assertEquals(2, DailyProgress.currentStreak(map, today))
    }

    @Test
    fun `기록이 없으면 스트릭은 0이다`() {
        assertEquals(0, DailyProgress.currentStreak(emptyMap(), today))
    }

    @Test
    fun `그날의 목표가 완료율의 분모다`() {
        // §10.1 — 설정에서 읽으면 예산이 바뀐 순간 과거 완료율이 소급 오염된다.
        val log = DailySpeechLog(date = today, dailyTargetSec = 300, achievedSec = 360)
        assertEquals(1.2, log.completionRate)

        val laterBudgetChange = log.copy(dailyTargetSec = 600)
        assertEquals(0.6, laterBudgetChange.completionRate)
    }

    @Test
    fun `오늘 로그가 없으면 다음 예산을 계산하지 않는다`() {
        assertNull(DailyProgress.nextDayBudgetSec(emptyMap(), today))
    }

    @Test
    fun `3일차부터 완료율에 따라 예산이 조정된다`() {
        val map = logs(
            today to 300,
            today.minusDays(1) to 300,
            today.minusDays(2) to 300,
        )
        assertEquals(330, DailyProgress.nextDayBudgetSec(map, today))
    }
}
