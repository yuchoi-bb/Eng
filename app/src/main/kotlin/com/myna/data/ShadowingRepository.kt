package com.myna.data

import com.myna.core.daily.DailyProgress
import com.myna.core.daily.DailySpeechLog
import com.myna.core.model.UserSettings
import com.myna.core.model.PlanPosition
import com.myna.core.model.SentenceId
import com.myna.core.model.VideoPlan
import com.myna.core.notes.FieldNote
import com.myna.core.notes.NoteSituation
import com.myna.core.notes.NotePractice
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

    /** 업데이트를 확인했다. 결과는 설정 화면에 보여 줘서 업데이트가 안 올 때 이유를 알 수 있게 한다. */
    public fun recordUpdateCheck(epochMs: Long, outcome: String) {
        mutate { it.copy(lastUpdateCheckEpochMs = epochMs, lastUpdateOutcome = outcome) }
    }

    // ---------- 현장 메모 ----------

    /**
     * 막힌 순간을 기록한다. 한국어 한 줄이면 충분하다 —
     * 영어로 적을 수 있었다면 애초에 막히지 않았다.
     */
    public fun addFieldNote(koreanMemo: String, situation: NoteSituation, nowEpochMs: Long): FieldNote {
        val note = FieldNote(
            id = "n${nowEpochMs}_${_state.value.fieldNotes.size}",
            koreanMemo = koreanMemo.trim(),
            situation = situation,
            createdAtEpochMs = nowEpochMs,
        )
        mutate { it.copy(fieldNotes = listOf(note) + it.fieldNotes) }
        return note
    }

    /**
     * 영어 문장을 채워 연습 대상으로 만든다.
     *
     * 계획까지 함께 만들어 저장하므로, 채우는 즉시 다른 영상과 똑같이 세션을 돌릴 수 있다.
     */
    public fun resolveFieldNote(noteId: String, englishText: String, nowEpochMs: Long): VideoPlan? {
        val note = _state.value.fieldNotes.firstOrNull { it.id == noteId } ?: return null
        val filled = note.copy(englishText = englishText.trim(), resolvedAtEpochMs = nowEpochMs)
        val plan = NotePractice.toPlan(filled, _state.value.settings.dailyTargetSec) ?: return null

        mutate { current ->
            current.copy(
                fieldNotes = current.fieldNotes.map {
                    if (it.id == noteId) filled.copy(practicePlanId = plan.id.value) else it
                },
                plans = current.plans + (plan.id.value to plan),
            )
        }
        return plan
    }

    public fun deleteFieldNote(noteId: String) {
        mutate { it.copy(fieldNotes = it.fieldNotes.filterNot { note -> note.id == noteId }) }
    }

    public fun today(date: LocalDate): DailySpeechLog =
        DailyProgress.logFor(_state.value.dailyLogs, date, _state.value.settings.dailyTargetSec)

    public fun streak(today: LocalDate): Int = DailyProgress.currentStreak(_state.value.dailyLogs, today)

    /**
     * 한 문장을 끝낼 때마다 부른다 — 발화 시간·카운트·이어서 할 자리를 **그 자리에서** 적는다.
     *
     * 세션이 끝날 때 한꺼번에 적으면 전화가 오거나 앱이 꺼졌을 때 그 세션의 진행이
     * 통째로 사라진다.
     *
     * 오늘 로그는 FIRESTORE_SCHEMA §2가 요구하는 **누적 합산**이다. S0는 단일 기기라 덧셈으로
     * 충분하지만, S1에서 이 자리가 `FieldValue.increment()`가 된다.
     *
     * @param nextPosition 다음에 이어서 할 자리. null이면 목표 회차를 다 끝낸 것이다.
     * @param savesPosition 오프라인 복습처럼 일부 문장만 도는 세션은 자리를 적지 않는다 —
     *   건너뛴 문장이 영영 건너뛰어진다.
     */
    public fun recordCount(
        date: LocalDate,
        planId: String,
        sentenceIndex: Int,
        achievedSec: Int,
        nextPosition: PlanPosition?,
        savesPosition: Boolean,
    ) {
        mutate { current ->
            val existing = DailyProgress.logFor(current.dailyLogs, date, current.settings.dailyTargetSec)
            val updated = existing.copy(
                achievedSec = existing.achievedSec + achievedSec,
                countedUtterances = existing.countedUtterances + 1,
                videoIds = existing.videoIds + planId,
            )
            val plan = current.plans[planId] ?: return@mutate current.copy(
                dailyLogs = current.dailyLogs + (date to updated),
            )
            val progressed = plan.copy(completedReps = plan.completedReps + 1).let {
                when {
                    !savesPosition -> it
                    nextPosition == null -> it.copy(resumeAt = null, completedRounds = it.completedRounds + 1)
                    else -> it.copy(resumeAt = nextPosition)
                }
            }
            current.copy(
                dailyLogs = current.dailyLogs + (date to updated),
                plans = current.plans + (planId to progressed),
                // 한 번 이상 완주한 문장 — 오프라인에서 무엇을 복습할 수 있는지가 여기서 나온다.
                clearedSentenceIds = current.clearedSentenceIds + SentenceId.of(plan.id, sentenceIndex).value,
            )
        }
    }

    /** "외웠어요" / "헷갈려요". 외운 문장은 다음 회차부터 듣지 않고 바로 말한다. */
    public fun setMemorized(planId: String, sentenceIndex: Int, memorized: Boolean) {
        mutate { current ->
            val plan = current.plans[planId] ?: return@mutate current
            val sentences = if (memorized) {
                plan.memorizedSentences + sentenceIndex
            } else {
                plan.memorizedSentences - sentenceIndex
            }
            current.copy(plans = current.plans + (planId to plan.copy(memorizedSentences = sentences)))
        }
    }

    /** §4.7 — 하루가 끝난 뒤 다음 날 예산을 반영한다. */
    public fun applyBudgetAdaptation(today: LocalDate) {
        val next = DailyProgress.nextDayBudgetSec(_state.value.dailyLogs, today) ?: return
        updateSettings { it.copy(dailyTargetSec = next) }
    }
}
