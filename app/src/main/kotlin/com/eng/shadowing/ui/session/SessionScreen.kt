package com.eng.shadowing.ui.session

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.eng.shadowing.core.model.VideoPlan
import com.eng.shadowing.core.session.ShadowingStage
import com.eng.shadowing.ui.ProgressBar

/**
 * 반복 세션 화면. REQUIREMENTS §4.5 / §7.2.
 *
 * 탭이 필요 없는 무한 루프다. 사용자가 누르는 버튼은 "이번 발화 끝" 하나뿐이고,
 * 나머지는 재생 종료와 녹음 종료가 스스로 다음 회차를 부른다.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun SessionScreen(
    plan: VideoPlan,
    mediaUri: Uri,
    playbackRate: Float,
    showTransliteration: Boolean,
    onKeepScreenOn: (Boolean) -> Unit,
    onFinished: (achievedSec: Int, counts: Int) -> Unit,
) {
    val context = LocalContext.current
    val controller = remember(plan.id.value) { SessionController(context, plan, mediaUri, playbackRate) }
    val ui by controller.uiState

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (granted) controller.start()
    }

    // §4.5 — 세션 중에는 화면을 켜 둔다.
    DisposableEffect(Unit) {
        onKeepScreenOn(true)
        onDispose {
            onKeepScreenOn(false)
            controller.release()
        }
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            controller.start()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(ui.finished) {
        if (ui.finished) onFinished(controller.achievedSec, controller.completedCounts)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    player = controller.playerInstance
                }
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )

        Spacer(Modifier.height(16.dp))

        // §4.5 — 세그먼트 링 대신 선형 진행바를 쓴다. 남은 횟수는 숫자로 같이 보여 준다.
        ProgressBar(fraction = ui.progressFraction)
        Text(
            "${ui.completedCounts} / ${ui.targetCounts} · 남은 ${ui.remainingCounts}회",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(20.dp))
        Text(stageLabel(ui.stage), style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth()) {
                if (ui.showSubtitle) {
                    // §7.2 — 1·2단계는 자막을 보여 주고 3단계는 숨긴다.
                    Text(
                        ui.sentenceText,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val transliteration = ui.transliterationKo
                    if (showTransliteration && transliteration != null) {
                        Text(
                            transliteration,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                } else {
                    Text(
                        "자막 없이 말해 보세요",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // §8 왕초보 튜닝 — 한글 뜻은 단계와 무관하게 병기한다.
                Text(
                    ui.translationKo,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (ui.recording) {
            Button(onClick = { controller.finishRecording() }, modifier = Modifier.fillMaxWidth()) {
                Text("말하기 끝")
            }
        } else {
            Text(
                "재생 중…",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
        }

        // L1 — 원본과 내 녹음을 번갈아 듣는다.
        TextButton(
            onClick = { controller.playbackComparison() },
            enabled = !ui.recording && ui.hasRecording,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("내 녹음과 비교해 듣기")
        }

        TextButton(onClick = { onFinished(controller.achievedSec, controller.completedCounts) }) {
            Text("여기까지 하기")
        }
    }
}

private fun stageLabel(stage: ShadowingStage): String = when (stage) {
    ShadowingStage.LISTEN -> "1단계 · 듣기만"
    ShadowingStage.SHADOW_WITH_TEXT -> "2단계 · 보면서 따라 말하기"
    ShadowingStage.SHADOW_NO_TEXT -> "3단계 · 자막 끄고 따라 말하기"
}
