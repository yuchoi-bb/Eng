package com.myna.ui.entry

import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.myna.media.SegmentPlayer
import com.myna.media.SourcePlayback
import com.myna.media.YouTubeSourcePlayback
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * 문장 편집 화면의 미리듣기 재생기.
 *
 * 세션과 같은 재생기를 쓴다 — 여기서 들리는 구간이 세션에서 들리는 구간과 같아야
 * 시작·끝을 맞춘 보람이 있다. 목록 안에 두면 스크롤로 화면 밖에 나갈 때 재생기가
 * 사라지므로 목록 위에 고정해 둔다.
 *
 * @param onPlayback 재생기가 준비되면 넘기고, 사라지면 null을 넘긴다.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun SegmentPreviewPlayer(
    youTubeVideoId: String?,
    videoUri: Uri?,
    onPlayback: (SourcePlayback?) -> Unit,
    onRefused: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnPlayback by rememberUpdatedState(onPlayback)
    val latestOnRefused by rememberUpdatedState(onRefused)

    // 영상을 바꾸면 재생기를 새로 만든다.
    key(youTubeVideoId, videoUri) {
        when {
            youTubeVideoId != null -> AndroidView(
                factory = { context ->
                    YouTubePlayerView(context).also { view ->
                        // 높이가 WRAP_CONTENT면 스스로 16:9로 줄인다. 주어진 틀을 그대로 쓰게 한다.
                        view.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        latestOnPlayback(
                            YouTubeSourcePlayback(
                                view = view,
                                videoId = youTubeVideoId,
                                onPlaybackRefused = { reason -> latestOnRefused(reason) },
                                onStalled = { reason -> latestOnRefused(reason) },
                            ),
                        )
                    }
                },
                onRelease = { view ->
                    latestOnPlayback(null)
                    view.release()
                },
                modifier = modifier,
            )

            videoUri != null -> AndroidView(
                factory = { context ->
                    val playback = SegmentPlayer(context).apply { mediaUri = videoUri }
                    latestOnPlayback(playback)
                    PlayerView(context).apply {
                        useController = false
                        player = playback.player
                        tag = playback
                    }
                },
                onRelease = { view ->
                    latestOnPlayback(null)
                    (view.tag as? SegmentPlayer)?.release()
                },
                modifier = modifier,
            )
        }
    }
}
