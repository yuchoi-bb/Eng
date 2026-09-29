package com.myna.ui.session

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.myna.core.model.PlanPosition
import com.myna.core.model.VideoPlan
import com.myna.core.session.SessionEvent
import com.myna.core.session.SessionOptions
import com.myna.core.session.ShadowingStage
import com.myna.core.session.VoiceMode
import com.myna.media.SegmentPlayer
import com.myna.media.YouTubeSourcePlayback
import com.myna.ui.ProgressBar
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

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
    source: SessionSource?,
    options: SessionOptions,
    playbackRate: Float,
    showTransliteration: Boolean,
    dailyAlreadySec: Int,
    dailyTargetSec: Int,
    /** 이어서 할 자리. null이면 처음부터. */
    startAt: PlanPosition?,
    /** 오프라인 복습에서 들어 본 문장만 돌 때. null이면 전부. */
    sentenceFilter: Set<Int>?,
    onKeepScreenOn: (Boolean) -> Unit,
    /** 한 문장을 끝낼 때마다 — 그 자리에서 저장한다. */
    onCountCompleted: (SessionEvent.CountCompleted) -> Unit,
    onMemorizedChanged: (sentenceIndex: Int, memorized: Boolean) -> Unit,
    /** 다음 문장으로 건너뛰었다. null이면 마지막 문장이었다. */
    onSentenceSkipped: (next: PlanPosition?) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    // 컨트롤러는 세션 동안 한 번만 만든다. 콜백은 최신 것을 부르도록 감싸 둔다.
    val latestOnCount by rememberUpdatedState(onCountCompleted)
    val latestOnMemorized by rememberUpdatedState(onMemorizedChanged)
    val latestOnSkipped by rememberUpdatedState(onSentenceSkipped)
    val controller = remember(plan.id.value) {
        SessionController(
            context = context,
            plan = plan,
            playbackRate = playbackRate,
            options = options,
            // 세션을 시작할 때의 값으로 고정한다. 카운트마다 저장되므로 매번 새 값을 받으면
            // 이번 세션의 발화 시간이 두 번 더해진다.
            dailyAlreadySec = dailyAlreadySec,
            dailyTargetSec = dailyTargetSec,
            startAt = startAt,
            sentenceFilter = sentenceFilter,
            onCountCompleted = { latestOnCount(it) },
            onMemorizedChanged = { index, memorized -> latestOnMemorized(index, memorized) },
            onSentenceSkipped = { next -> latestOnSkipped(next) },
        )
    }
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
        when {
            // 무음 모드는 녹음하지 않으므로 마이크 권한을 묻지 않는다.
            !options.recordsVoice -> controller.start()
            permissionGranted -> controller.start()
            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 진행은 문장마다 이미 저장됐다. 끝나면 나가기만 한다.
    LaunchedEffect(ui.finished) {
        if (ui.finished) onExit()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 두 소스가 서로 다른 재생기를 쓴다. 뷰가 만들어진 뒤에야 재생기를 붙일 수 있으므로
        // factory 안에서 컨트롤러에 연결한다 — §F-1은 유튜브를 공식 임베드로만 재생하게 한다.
        //
        // 플레이어는 자막·버튼을 다 놓고 **남는 세로 공간**을 쓴다. 고정 비율로 잡으면 세로
        // 영상(숏츠)이 화면 아래의 버튼을 밀어낸다.
        // when의 대상이 표현식이면 각 분기에서 스마트 캐스트가 되지 않는다. 지역 val로 받는다.
        val activeSource = source.takeIf { options.sourceAudioAvailable && ui.playsSource }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when (activeSource) {
                // 원본이 없는 세션(오프라인 복습, 현장 메모 연습)은 재생할 것이 없다.
                null -> Text(
                    if (options.sourceAudioAvailable) "" else "원본 없이 연습합니다",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                is SessionSource.Upload -> {
                    // 람다 안으로 스마트 캐스트를 끌고 들어가지 않는다. 값을 먼저 꺼내 둔다.
                    val mediaUri = activeSource.uri
                    AndroidView(
                        factory = { viewContext ->
                            val playback = SegmentPlayer(viewContext).apply { this.mediaUri = mediaUri }
                            controller.attachSource(playback)
                            // PlayerView는 영상 고유 비율로 맞춰 그린다(RESIZE_MODE_FIT) — 가로든 세로든.
                            PlayerView(viewContext).apply {
                                useController = false
                                player = playback.player
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                is SessionSource.YouTube -> {
                    val videoId = activeSource.videoId
                    AndroidView(
                        factory = { viewContext ->
                            YouTubePlayerView(viewContext).also { view ->
                                // YouTubePlayerView는 높이가 WRAP_CONTENT면 스스로 16:9로 줄인다.
                                // 세로 틀을 그대로 쓰게 MATCH_PARENT로 못 박는다.
                                view.layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                controller.attachSource(
                                    YouTubeSourcePlayback(
                                        view = view,
                                        videoId = videoId,
                                        // 임베드가 거부되면 재생 완료 콜백이 오지 않는다.
                                        // 그대로 두면 세션이 듣기 단계에서 굳는다.
                                        onPlaybackRefused = controller::onSourceRefused,
                                        // 오류 없이 썸네일에서 멈춘 경우. 알리지 않으면 "재생 중…"에서 굳는다.
                                        onStalled = controller::onSourceStalled,
                                    ),
                                )
                            }
                        },
                        onRelease = { view -> view.release() },
                        // 이 앱의 기본 소스는 숏츠다 — 세로 9:16 틀에 담는다.
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(9f / 16f, matchHeightConstraintsFirst = true),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // §4.5 — 세그먼트 링 대신 선형 진행바를 쓴다. 남은 횟수는 숫자로 같이 보여 준다.
        ProgressBar(fraction = ui.progressFraction)
        // "남은 306회"는 쓸 수 없는 숫자다. 회차와 문장 번호로 보여 준다.
        Text(
            "${ui.currentRep}회차 / ${ui.totalReps}회 · 문장 ${ui.currentSentenceNumber}/${ui.sentenceCount}" +
                if (ui.memorizedCount > 0) " · 외운 문장 ${ui.memorizedCount}" else "",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (dailyTargetSec > 0) {
            Text(
                if (ui.dailyGoalMet) {
                    "오늘 목표를 채웠습니다. 여기서 멈춰도 됩니다."
                } else {
                    "오늘 목표까지 ${ui.remainingDailySec}초"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (ui.dailyGoalMet) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        ui.stallNotice?.let { notice ->
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        ui.playbackNotice?.let { notice ->
            Text(
                "$notice 자막만 보고 이어서 연습합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(stageLabel(ui, options), style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
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

        Spacer(Modifier.height(12.dp))

        if (ui.recording) {
            Button(onClick = { controller.finishRecording() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (options.voiceMode == VoiceMode.WHISPER) "말했어요" else "말하기 끝")
            }
        } else {
            Text(
                // 외운 문장의 비교 재생 — 방금 내가 한 말과 같은지 들어 본다.
                if (ui.checking) "원본과 같은지 들어 보세요…" else "재생 중…",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
        }

        Row(Modifier.fillMaxWidth()) {
            // 못 알아들었으면 이 문장을 다시 듣는다. 카운트는 오르지 않고 같은 단계를 다시 한다.
            OutlinedButton(
                onClick = { controller.replayCurrent() },
                enabled = ui.playsSource && !ui.finished,
                modifier = Modifier.weight(1f),
            ) {
                Text("다시 듣기")
            }
            Spacer(Modifier.width(8.dp))
            // 너무 쉽거나 따라 할 필요 없는 문장은 남은 회차를 건너뛴다. 건너뛴 회차는 세지 않는다.
            OutlinedButton(
                onClick = { controller.skipSentence() },
                enabled = !ui.finished,
                modifier = Modifier.weight(1f),
            ) {
                Text("다음 문장 ⏭")
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // L1 — 원본과 내 녹음을 번갈아 듣는다.
            TextButton(
                onClick = { controller.playbackComparison() },
                // 외운 문장의 비교 재생 중에 누르면 그 걸음이 카운트 없이 처음으로 돌아간다.
                enabled = !ui.recording && !ui.checking && ui.hasRecording,
                modifier = Modifier.weight(1f),
            ) {
                Text("내 녹음 비교")
            }
            if (ui.memorized) {
                // 막혔으면 외운 문장 표시를 풀고 듣고 따라 하기로 돌아간다.
                TextButton(onClick = { controller.unmarkMemorized() }, modifier = Modifier.weight(1f)) {
                    Text("헷갈려요")
                }
            } else {
                // 자막 없이 말할 수 있으면 외운 것이다. 다음 회차부터 듣지 않고 바로 말한다.
                TextButton(
                    onClick = { controller.markMemorized() },
                    enabled = !ui.finished,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("외웠어요")
                }
            }
            // 진행은 문장마다 저장되므로 언제 나가도 다음에 이 자리에서 이어서 한다.
            TextButton(onClick = onExit, modifier = Modifier.weight(1f)) {
                Text(if (ui.dailyGoalMet) "목표 달성 ✓" else "여기까지")
            }
        }
    }
}

private fun stageLabel(ui: SessionUiState, options: SessionOptions): String {
    val whisper = options.voiceMode == VoiceMode.WHISPER
    val action = when (ui.stage) {
        ShadowingStage.LISTEN -> "듣기만"
        ShadowingStage.SHADOW_WITH_TEXT -> if (whisper) "보면서 입모양으로" else "듣고 보면서 따라 말하기"
        ShadowingStage.SHADOW_NO_TEXT -> if (whisper) "자막 끄고 입모양으로" else "자막 끄고 따라 말하기"
        ShadowingStage.RECALL -> if (whisper) "외운 문장 · 입모양으로 바로" else "외운 문장 · 듣지 않고 바로 말하기"
    }
    // 외운 문장은 단계가 하나뿐이다. "1/1단계"는 군더더기다.
    return if (ui.stageCount <= 1) action else "${ui.stageNumber}/${ui.stageCount}단계 · $action"
}
