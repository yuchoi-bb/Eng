package com.myna.data

import com.myna.core.daily.DailyProgress
import com.myna.core.daily.DailySpeechLog
import com.myna.core.model.UserSettings
import com.myna.core.model.VideoPlan
import com.myna.core.store.AppState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * 앱 상태의 단일 출처. 모든 변경은 [mutate]를 거쳐 저장까지 함께 일어난다.
 *
 * 날짜는 **호출자가 넘긴다.** §4.6이 자정 롤오버를 금지했고 판정은 조회 시점에 하므로,
 * 저장소가 시계를 직접 읽으면 테스트도 못 하고 기기 시간 변경에도 취약해진다.
 */
public class ShadowingRepository(private val store: LocalStore) {

    private val _state = MutableStateFlow(store.load())
    public val state: StateFlow<AppState> = _state.asStateFlow()

    private fun mutate(block: (AppState) -> AppState) {
        val next = block(_state.value)
        _state.value = next
        store.save(next)
    }

    public fun updateSettings(block: (UserSettings) -> UserSettings) {
        mutate { it.copy(settings = block(it.settings)) }
    }

    /** 업로드 영상의 로컬 URI는 계획과 분리해 둔다 — §10.1. */
    public fun putPlan(plan: VideoPlan, localMediaUri: String?) {
        mutate { current ->
            current.copy(
                plans = current.plans + (plan.id.value to plan),
                localMediaUris = if (localMediaUri == null) {
                    current.localMediaUris
                } else {
                    current.localMediaUris + (plan.id.value to localMediaUri)
                },
            )
        }
    }

    public fun localMediaUri(planId: String): String? = _state.value.localMediaUris[planId]

    public fun recordUpdateCheck(epochMs: Long) {
        mutate { it.copy(lastUpdateCheckEpochMs = epochMs) }
    }

    public fun skipUpdateVersion(version: String) {
        mutate { it.copy(skippedUpdateVersion = version) }
    }

    public fun today(date: LocalDate): DailySpeechLog =
        DailyProgress.logFor(_state.value.dailyLogs, date, _state.value.settings.dailyTargetSec)

    public fun streak(today: LocalDate): Int = DailyProgress.currentStreak(_state.value.dailyLogs, today)

    /**
     * 세션에서 쌓은 발화 시간과 카운트를 오늘 로그에 더한다.
     *
     * FIRESTORE_SCHEMA §2가 요구하는 **누적 합산**이다. S0는 단일 기기라 덧셈으로 충분하지만,
     * S1에서 이 자리가 `FieldValue.increment()`가 된다. 값을 통째로 덮어쓰는 형태로 짜 두면
     * 그때 태블릿 간 발화 시간이 사라진다.
     */
    public fun addProgress(date: LocalDate, planId: String, achievedSec: Int, counts: Int) {
        mutate { current ->
            val existing = DailyProgress.logFor(current.dailyLogs, date, current.settings.dailyTargetSec)
            val updated = existing.copy(
                achievedSec = existing.achievedSec + achievedSec,
                countedUtterances = existing.countedUtterances + counts,
                videoIds = existing.videoIds + planId,
            )
            val plan = current.plans[planId]
            current.copy(
                dailyLogs = current.dailyLogs + (date to updated),
                plans = if (plan == null) {
                    current.plans
                } else {
                    current.plans + (planId to plan.copy(completedReps = plan.completedReps + counts))
                },
            )
        }
    }

    /** §4.7 — 하루가 끝난 뒤 다음 날 예산을 반영한다. */
    public fun applyBudgetAdaptation(today: LocalDate) {
        val next = DailyProgress.nextDayBudgetSec(_state.value.dailyLogs, today) ?: return
        updateSettings { it.copy(dailyTargetSec = next) }
    }
}
