package com.myna.ui.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.myna.core.model.VideoPlan
import com.myna.core.offline.OfflineAvailability
import com.myna.core.offline.OfflinePlanStatus
import com.myna.core.session.RepsCalculator
import com.myna.core.session.SessionOptions
import com.myna.core.session.VoiceMode
import com.myna.ui.formatDuration

/**
 * 세션 시작 화면. REQUIREMENTS §4.2.
 *
 * 자동 모드에서도 제시값은 **여기서 사용자가 덮어쓸 수 있다.**
 */
@Composable
internal fun SessionSetupScreen(
    plan: VideoPlan,
    dailyTargetSec: Int,
    achievedSec: Int,
    online: Boolean,
    offlineStatus: OfflinePlanStatus,
    needsSourceAudio: Boolean,
    onStart: (VideoPlan, SessionOptions) -> Unit,
    onBack: () -> Unit,
) {
    var autoReps by remember { mutableStateOf(plan.autoReps) }
    var manualReps by remember { mutableStateOf(plan.targetReps.toString()) }
    var whisper by remember { mutableStateOf(false) }

    // 원본을 들을 수 있는 조건: 회선이 필요한 소스라면 회선이 살아 있어야 한다.
    val sourceAudioAvailable = !needsSourceAudio || online

    val speechSec = RepsCalculator.speechSec(plan.transcript)
    val effectiveReps = if (autoReps) plan.suggestedReps else manualReps.toIntOrNull() ?: plan.suggestedReps
    val projectedSec = effectiveReps * speechSec

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("세션 준비", style = MaterialTheme.typography.headlineSmall)

        Spacer(Modifier.height(16.dp))
        Text("문장 ${plan.sentences.size}개 · 발화 ${formatDuration(speechSec)}")
        Text("유형 ${plan.transcript.type}", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("소리 내기 어려운 자리")
                Text(
                    "속삭이거나 입모양만 움직입니다. 녹음과 채점을 끄고 횟수만 셉니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = whisper, onCheckedChange = { whisper = it })
        }

        if (!sourceAudioAvailable) {
            Spacer(Modifier.height(12.dp))
            Text(
                offlineNotice(offlineStatus),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("횟수 자동 계산")
                Text(
                    "하루 목표에서 역산합니다 (${plan.suggestedReps}회 제시)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = autoReps, onCheckedChange = { autoReps = it })
        }

        if (!autoReps) {
            OutlinedTextField(
                value = manualReps,
                onValueChange = { manualReps = it.filter(Char::isDigit) },
                label = { Text("반복 횟수") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }

        Spacer(Modifier.height(20.dp))
        // §4.2.1 — 하루 목표는 여러 영상의 발화 시간이 누적되어 채워진다.
        Text(
            "이 세션 예상 ${formatDuration(projectedSec)} · " +
                "오늘 ${formatDuration(achievedSec)} / ${formatDuration(dailyTargetSec)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (achievedSec + projectedSec < dailyTargetSec) {
            // §4.2.1 — 영상 1개로 못 채우면 2번째 영상을 자동 제안한다.
            Text(
                "이 영상만으로는 오늘 목표를 채우지 못합니다. 끝나면 영상을 하나 더 권할게요.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("뒤로") }
            Button(
                onClick = {
                    onStart(
                        plan.copy(
                            autoReps = autoReps,
                            targetReps = effectiveReps.coerceAtLeast(1),
                        ),
                        SessionOptions(
                            sourceAudioAvailable = sourceAudioAvailable,
                            voiceMode = if (whisper) VoiceMode.WHISPER else VoiceMode.ALOUD,
                        ),
                    )
                },
                // 회선이 없고 들어 본 문장도 없으면 시작할 수 없다.
                enabled = sourceAudioAvailable ||
                    offlineStatus.availability != OfflineAvailability.NOT_READY,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (sourceAudioAvailable) "시작" else "오프라인으로 복습")
            }
        }
    }
}

/**
 * 회선 없이 들어왔을 때의 안내.
 *
 * 한 번도 들어 본 적 없는 문장은 자막만 보고 따라 해 봐야 쉐도잉이 아니라 읽기다.
 * 그래서 들어 본 문장이 하나도 없으면 시작을 막는다.
 */
private fun offlineNotice(status: OfflinePlanStatus): String = when (status.availability) {
    OfflineAvailability.READY ->
        "회선이 없어 원본을 재생할 수 없습니다. 이미 들어 본 문장이라 자막만으로 복습할 수 있습니다."

    OfflineAvailability.PARTIAL ->
        "회선이 없습니다. 들어 본 적 있는 ${status.practicableSentences}문장만 복습합니다 " +
            "(전체 ${status.totalSentences}문장)."

    OfflineAvailability.NOT_READY ->
        "회선이 없고 이 영상은 아직 한 번도 들어 보지 않았습니다. " +
            "처음 듣는 문장은 자막만 보고 따라 해도 효과가 없으니, 연결된 뒤에 시작해 주세요."
}
