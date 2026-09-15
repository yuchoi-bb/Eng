package com.eng.shadowing.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.eng.shadowing.core.playback.PlaybackSegment

/**
 * 구간 반복 재생 (L1). 종료 시점 판정을 폴링이 아니라 [MediaItem.ClippingConfiguration]에 맡긴다.
 *
 * 폴링으로 `currentPosition`을 감시하면 프레임 간격만큼 늦게 멈춰 문장 끝이 다음 문장을 물고
 * 들어온다. 클리핑을 걸면 플레이어가 정확한 지점에서 `STATE_ENDED`를 준다.
 */
@OptIn(UnstableApi::class)
public class SegmentPlayer(context: Context) {

    public val player: ExoPlayer = ExoPlayer.Builder(context).build()

    private var onSegmentFinished: (() -> Unit)? = null

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        onSegmentFinished?.invoke()
                    }
                }
            },
        )
    }

    /**
     * 한 구간을 재생한다.
     *
     * @param segment 패딩이 이미 적용된 구간 — [com.eng.shadowing.core.playback.SegmentPadding] 참조.
     * @param speed 재생 속도. §8의 왕초보 기본값은 0.75배다.
     */
    public fun play(
        uri: Uri,
        segment: PlaybackSegment,
        speed: Float,
        onFinished: () -> Unit,
    ) {
        onSegmentFinished = onFinished
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(segment.startMs.toLong())
                        .setEndPositionMs(segment.endMs.toLong())
                        .build(),
                )
                .build(),
        )
        player.setPlaybackSpeed(speed)
        player.prepare()
        player.playWhenReady = true
    }

    /** L1 A/B 비교 — 내 녹음은 속도 보정 없이 그대로 듣는다. */
    public fun playRecording(uri: Uri, onFinished: () -> Unit) {
        onSegmentFinished = onFinished
        player.setMediaItem(MediaItem.fromUri(uri))
        player.setPlaybackSpeed(1.0f)
        player.prepare()
        player.playWhenReady = true
    }

    public fun pause() {
        player.playWhenReady = false
    }

    public fun release() {
        onSegmentFinished = null
        player.release()
    }
}
