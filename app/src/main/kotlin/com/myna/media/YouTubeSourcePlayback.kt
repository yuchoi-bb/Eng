package com.myna.media

import com.myna.core.playback.PlaybackSegment
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * 유튜브 구간 재생 (§F-1).
 *
 * **경계 정밀도가 업로드 경로보다 거칠다.** IFrame API는 초 단위로 위치를 알려 주므로
 * 구간 끝을 정확히 잘라낼 수 없고, `onCurrentSecond` 콜백이 오는 시점에야 멈춘다.
 * TRANSCRIPTION_SCHEMA §1.1이 유튜브 경로에 `timestampUnit=SECOND`와 ±300ms 패딩을
 * 지정한 이유가 이것이다. 잘림보다 조금 넘치는 편이 쉐도잉에는 낫다.
 */
internal class YouTubeSourcePlayback(
    view: YouTubePlayerView,
    private val videoId: String,
    /**
     * 임베드 재생이 거부됐을 때 알린다.
     *
     * 유튜브는 영상마다 임베드 허용 여부가 다르다. 거부되면 플레이어가 "이 동영상은 볼 수
     * 없습니다 (152-4)"를 띄우는데, **재생 완료 콜백이 영영 오지 않는다.** 그대로 두면
     * 세션이 듣기 단계에서 굳는다.
     */
    private val onPlaybackRefused: (String) -> Unit = {},
) : SourcePlayback {

    private var player: YouTubePlayer? = null
    private var endSeconds: Float = NO_TARGET
    private var onSegmentFinished: (() -> Unit)? = null
    private var pendingPlay: ((YouTubePlayer) -> Unit)? = null

    init {
        view.enableAutomaticInitialization = false
        view.initialize(
            object : AbstractYouTubePlayerListener() {
                override fun onReady(youTubePlayer: YouTubePlayer) {
                    player = youTubePlayer
                    youTubePlayer.cueVideo(videoId, 0f)
                    // 준비 전에 들어온 재생 요청을 흘리지 않는다.
                    pendingPlay?.let { it(youTubePlayer) }
                    pendingPlay = null
                }

                override fun onError(
                    youTubePlayer: YouTubePlayer,
                    error: PlayerConstants.PlayerError,
                ) {
                    endSeconds = NO_TARGET
                    pendingPlay = null
                    onPlaybackRefused(describe(error))
                }

                override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                    val target = endSeconds
                    if (target > NO_TARGET && second >= target) {
                        endSeconds = NO_TARGET
                        youTubePlayer.pause()
                        onSegmentFinished?.invoke()
                    }
                }
            },
            IFramePlayerOptions.Builder()
                .controls(0)   // 세션 중에는 사용자가 재생을 건드리지 않는다
                .rel(0)
                .build(),
        )
    }

    override fun playSegment(segment: PlaybackSegment, speed: Float, onFinished: () -> Unit) {
        onSegmentFinished = onFinished
        endSeconds = segment.endMs / 1000f

        val start = segment.startMs / 1000f
        val action: (YouTubePlayer) -> Unit = { youTubePlayer ->
            youTubePlayer.setPlaybackRate(playbackRateFor(speed))
            youTubePlayer.loadVideo(videoId, start)
        }

        val ready = player
        if (ready == null) pendingPlay = action else action(ready)
    }

    override fun pause() {
        endSeconds = NO_TARGET
        player?.pause()
    }

    override fun release() {
        onSegmentFinished = null
        pendingPlay = null
        player = null
    }

    /**
     * IFrame Player는 임의의 배속을 받지 않고 정해진 값만 받는다.
     * §8의 왕초보 기본값 0.75배는 그 목록 안에 있다.
     */
    private fun playbackRateFor(speed: Float): PlayerConstants.PlaybackRate = when {
        speed <= 0.3f -> PlayerConstants.PlaybackRate.RATE_0_25
        speed <= 0.6f -> PlayerConstants.PlaybackRate.RATE_0_5
        speed <= 0.85f -> PlayerConstants.PlaybackRate.RATE_0_75
        speed <= 1.2f -> PlayerConstants.PlaybackRate.RATE_1
        speed <= 1.4f -> PlayerConstants.PlaybackRate.RATE_1_25
        speed <= 1.75f -> PlayerConstants.PlaybackRate.RATE_1_5
        else -> PlayerConstants.PlaybackRate.RATE_2
    }

    private fun describe(error: PlayerConstants.PlayerError): String = when (error) {
        PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER ->
            "이 영상은 앱 안에서 재생할 수 없습니다. 영상 주인이 외부 재생을 막아 둔 경우입니다."

        PlayerConstants.PlayerError.VIDEO_NOT_FOUND ->
            "영상을 찾을 수 없습니다. 비공개로 바뀌었거나 삭제됐을 수 있습니다."

        PlayerConstants.PlayerError.INVALID_PARAMETER_IN_REQUEST ->
            "이 영상은 앱 안에서 재생할 수 없습니다."

        PlayerConstants.PlayerError.HTML_5_PLAYER ->
            "플레이어를 띄우지 못했습니다."

        PlayerConstants.PlayerError.UNKNOWN ->
            "영상을 재생하지 못했습니다."
    }

    private companion object {
        const val NO_TARGET = -1f
    }
}
