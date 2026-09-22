package com.myna.core.daily

import com.myna.core.budget.BudgetAdaptation
import com.myna.core.store.LocalDateSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * 하루치 발화 기록. REQUIREMENTS §9.3 + §10.1.
 *
 * [dailyTargetSec]는 **그날 적용된 목표의 스냅샷**이다. 설정에서만 읽으면 §4.7 적응 로직이
 * 예산을 바꾼 순간 과거 완료율이 소급해서 틀려지고, 그 완료율을 다시 입력으로 쓰므로
 * 되먹임이 오염된다.
 */
@Serializable
public data class DailySpeechLog(
    @Serializable(with = LocalDateSerializer::class)
    val date: LocalDate,
    val dailyTargetSec: Int,
    val achievedSec: Int = 0,
    /** [achievedSec]에 포함된 값 중 복습 문장 몫 — §7.1. */
    val reviewSec: Int = 0,
    val countedUtterances: Int = 0,
    val videoIds: Set<String> = emptySet(),
) {
    /** §4.2.1 — 예산은 상한이 아니라 목표값이므로 1.0을 넘을 수 있다. */
    val completionRate: Double
        get() = BudgetAdaptation.completionRate(achievedSec, dailyTargetSec)

    val isGoalMet: Boolean get() = completionRate >= 1.0
}

/**
 * 일일 로그 조회와 스트릭 계산.
 *
 * **자정 WorkManager로 롤오버하지 않는다**(§4.6). 절전 모드나 기기 시간 변경 때 누락되기
 * 때문이다. 조회 시점에 [LocalDate] 키로 판정한다 — 그래서 여기 있는 것은 전부
 * 순수 함수이고, 기기 시계는 호출자가 주입한다.
 */
public object DailyProgress {

    /** 오늘 로그를 꺼내거나, 없으면 현재 목표로 새로 만든다. */
    public fun logFor(
        logs: Map<LocalDate, DailySpeechLog>,
        date: LocalDate,
        currentTargetSec: Int,
    ): DailySpeechLog = logs[date] ?: DailySpeechLog(date = date, dailyTargetSec = currentTargetSec)

    /**
     * F-5 스트릭 — 목표를 채운 날이 연속 며칠인가.
     *
     * 오늘을 아직 못 채웠어도 어제까지 이어졌다면 **스트릭은 살아 있다.** 그렇지 않으면
     * 매일 아침 0으로 보여 이탈을 부른다. 오늘을 채웠으면 오늘부터, 아니면 어제부터 센다.
     */
    public fun currentStreak(logs: Map<LocalDate, DailySpeechLog>, today: LocalDate): Int {
        val startedToday = logs[today]?.isGoalMet == true
        var cursor = if (startedToday) today else today.minusDays(1)
        var streak = 0
        while (logs[cursor]?.isGoalMet == true) {
            streak += 1
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /**
     * §4.7 적응 로직에 넘길 다음 날 예산.
     *
     * 완료율은 **시간 기준으로만** 본다(§4.2.1 — 수동 횟수는 사용자 의도이므로 제외).
     */
    public fun nextDayBudgetSec(logs: Map<LocalDate, DailySpeechLog>, today: LocalDate): Int? {
        val todayLog = logs[today] ?: return null
        return BudgetAdaptation.nextBudgetSec(
            currentBudgetSec = todayLog.dailyTargetSec,
            completionRate = todayLog.completionRate,
            daysLogged = logs.size,
        )
    }
}
