package com.myna.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.myna.core.playback.PlaybackSegment

/**
 * 업로드 영상의 구간 재생 (F-2, L1).
 *
 * 종료 시점 판정을 폴링이 아니라 [MediaItem.ClippingConfiguration]에 맡긴다. 폴링으로
 * `currentPosition`을 감시하면 프레임 간격만큼 늦게 멈춰 문장 끝이 다음 문장을 물고 들어온다.
 * 클리핑을 걸면 플레이어가 정확한 지점에서 `STATE_ENDED`를 준다.
 *
 * 이 정밀도는 업로드 경로에서만 가능하다 — 유튜브는 [YouTubeSourcePlayback] 참조.
 */
@OptIn(UnstableApi::class)
internal class SegmentPlayer(
    context: Context,
    /**
     * 영상을 열지 못했다. 알리지 않으면 구간 끝(STATE_ENDED)이 영영 오지 않아 화면이
     * "재생 중…"에서 굳는다.
     */
    private val onError: (String) -> Unit = {},
) : SourcePlayback {

    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    /** 재생할 로컬 영상. [playSegment]를 부르기 전에 정해져 있어야 한다. */
    var mediaUri: Uri? = null

    private var onSegmentFinished: (() -> Unit)? = null

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        onSegmentFinished?.invoke()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    onSegmentFinished = null
                    onError(
                        if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION
                        ) {
                            "이 기기에서 영상을 열 수 없습니다. 준비 화면에서 [영상 다시 연결]을 눌러 주세요."
                        } else {
                            "영상을 재생하지 못했습니다 (${error.errorCodeName})."
                        },
                    )
                }
            },
        )
    }

    /**
     * 한 구간을 재생한다.
     *
     * @param segment 패딩이 이미 적용된 구간 — [com.myna.core.playback.SegmentPadding] 참조.
     * @param speed 재생 속도. §8의 왕초보 기본값은 0.75배다.
     */
    override fun playSegment(segment: PlaybackSegment, speed: Float, onFinished: () -> Unit) {
        val uri = mediaUri ?: return
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
    fun playRecording(uri: Uri, onFinished: () -> Unit) {
        onSegmentFinished = onFinished
        player.setMediaItem(MediaItem.fromUri(uri))
        player.setPlaybackSpeed(1.0f)
        player.prepare()
        player.playWhenReady = true
    }

    override fun pause() {
        player.playWhenReady = false
    }

    override fun release() {
        onSegmentFinished = null
        player.release()
    }
}
