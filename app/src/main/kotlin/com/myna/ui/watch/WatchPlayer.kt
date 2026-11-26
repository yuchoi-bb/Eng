package com.myna.ui.watch

import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * 영상을 그냥 보는 재생기 — 재생·멈춤·탐색 조작이 있다. 연습용 구간 재생기와 따로 둔다.
 *
 * @param startMs 이 위치부터. 작은 화면과 전체 화면을 오갈 때 보던 자리를 잇는다.
 * @param autoplay 열자마자 재생할지. 전체 화면은 재생, 준비 화면 안의 작은 화면은 대기.
 * @param onPosition 보던 위치. 다른 화면으로 넘어갈 때 이어 보게 기억해 둔다.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun WatchPlayer(
    youTubeVideoId: String?,
    videoUri: Uri?,
    startMs: Long,
    autoplay: Boolean,
    onPosition: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnPosition by rememberUpdatedState(onPosition)
    val lifecycleOwner = LocalLifecycleOwner.current

    key(youTubeVideoId, videoUri) {
        when {
            youTubeVideoId != null -> AndroidView(
                factory = { context ->
                    YouTubePlayerView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        enableAutomaticInitialization = false
                        initialize(
                            object : AbstractYouTubePlayerListener() {
                                override fun onReady(youTubePlayer: YouTubePlayer) {
                                    val start = startMs / 1000f
                                    if (autoplay) {
                                        youTubePlayer.loadVideo(youTubeVideoId, start)
                                    } else {
                                        youTubePlayer.cueVideo(youTubeVideoId, start)
                                    }
                                }

                                override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                                    latestOnPosition((second * 1000).toLong())
                                }
                            },
                            IFramePlayerOptions.Builder(context)
                                .controls(1)
                                .rel(0)
                                // 유튜브 자체 전체 화면 버튼은 끈다. 앱의 전체 화면을 쓴다 — 라이브러리의
                                // 전체 화면은 따로 붙이지 않으면 누르는 순간 앱이 죽는다.
                                .fullscreen(0)
                                .build(),
                        )
                    }
                },
                onRelease = { it.release() },
                modifier = modifier,
            )

            videoUri != null -> {
                val context = LocalContext.current
                val player = remember {
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(videoUri))
                        prepare()
                        seekTo(startMs)
                        playWhenReady = autoplay
                    }
                }
                DisposableEffect(player, lifecycleOwner) {
                    // 화면을 끄거나 다른 앱으로 가면 멈춘다.
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP) player.pause()
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        latestOnPosition(player.currentPosition)
                        player.release()
                    }
                }
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            this.player = player
                            useController = true
                        }
                    },
                    modifier = modifier,
                )
            }
        }
    }
}
