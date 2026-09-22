package com.myna.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myna.core.daily.DailySpeechLog
import com.myna.core.store.AppState
import com.myna.ui.ProgressBar
import com.myna.ui.formatDuration
import kotlin.math.roundToInt

/** F-5 일일 진행률 / 스트릭 + 영상 목록. */
@Composable
internal fun HomeScreen(
    state: AppState,
    today: DailySpeechLog,
    streak: Int,
    online: Boolean,
    noteCount: Int,
    onAddVideo: () -> Unit,
    onOpenPlan: (String) -> Unit,
    onCaptureNote: () -> Unit,
    onOpenNotes: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("오늘", style = MaterialTheme.typography.headlineSmall)

        // §4.2.1 — 예산은 상한이 아니라 목표값이다. 초과해도 막지 않고 120%처럼 보여 준다.
        val percent = (today.completionRate * 100).roundToInt()
        Text(
            "${formatDuration(today.achievedSec)} / ${formatDuration(today.dailyTargetSec)}  ($percent%)",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        ProgressBar(fraction = today.completionRate.toFloat())

        if (streak > 0) {
            Text("연속 $streak 일", modifier = Modifier.padding(top = 12.dp))
        }
        if (!online) {
            // 유튜브는 임베드 재생이라 회선 없이는 원본을 들려줄 수 없다.
            Text(
                "오프라인입니다. 이미 들어 본 문장만 복습할 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(20.dp))
        // 현장에서 막혔을 때 30초 안에 끝나야 한다. 그래서 홈 첫 화면에 둔다.
        Button(onClick = onCaptureNote, modifier = Modifier.fillMaxWidth()) {
            Text("지금 막혔어요")
        }
        TextButton(onClick = onOpenNotes, modifier = Modifier.fillMaxWidth()) {
            Text(if (noteCount > 0) "막힌 순간들 · 채울 것 ${noteCount}개" else "막힌 순간들")
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onAddVideo, modifier = Modifier.fillMaxWidth()) {
            Text("영상 추가하기")
        }

        Spacer(Modifier.height(24.dp))
        Text("내 영상", style = MaterialTheme.typography.titleMedium)

        val plans = state.plans.values.toList()
        if (plans.isEmpty()) {
            Text(
                "아직 없습니다. 영상을 추가하고 문장을 직접 적어 시작해 보세요.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            LazyColumn(Modifier.padding(top = 8.dp)) {
                items(plans) { plan ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onOpenPlan(plan.id.value) },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                plan.sentences.firstOrNull()?.text ?: plan.id.value,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "문장 ${plan.sentences.size}개 · 목표 ${plan.targetReps}회 · " +
                                    "누적 ${plan.completedReps}회",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
