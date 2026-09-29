package com.myna.media

import android.os.Handler
import android.os.Looper
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
    /**
     * 재생을 요청했는데 시작되지 않았다. 오류 없이 썸네일만 떠 있는 상태다.
     *
     * 이걸 알리지 않으면 재생 완료 콜백이 영영 오지 않아 화면은 "재생 중…"인 채로 멈춘다.
     * 거부와 달리 원본을 포기하지는 않는다 — 다시 누르면 나올 수 있다.
     */
    private val onStalled: (String) -> Unit = {},
) : SourcePlayback {

    private var player: YouTubePlayer? = null
    private var endSeconds: Float = NO_TARGET
    private var onSegmentFinished: (() -> Unit)? = null
    private var pendingPlay: ((YouTubePlayer) -> Unit)? = null

    /** 플레이어가 마지막으로 알린 상태. 재생이 실제로 시작됐는지 여기서 본다. */
    private var state: PlayerConstants.PlayerState = PlayerConstants.PlayerState.UNKNOWN

    private val handler = Handler(Looper.getMainLooper())

    /** 재생 요청마다 오른다. 지난 요청의 감시가 새 요청을 건드리지 않게 한다. */
    private var request = 0

    init {
        view.enableAutomaticInitialization = false
        view.initialize(
            object : AbstractYouTubePlayerListener() {
                override fun onReady(youTubePlayer: YouTubePlayer) {
                    player = youTubePlayer
                    val pending = pendingPlay
                    pendingPlay = null
                    if (pending != null) {
                        // 준비 전에 들어온 재생 요청이 있으면 곧장 재생한다. 썸네일을 먼저 띄우는
                        // cueVideo를 바로 앞에 보내면 두 명령이 겹쳐 썸네일에서 멈출 수 있다.
                        pending(youTubePlayer)
                    } else {
                        youTubePlayer.cueVideo(videoId, 0f)
                    }
                }

                override fun onStateChange(
                    youTubePlayer: YouTubePlayer,
                    state: PlayerConstants.PlayerState,
                ) {
                    this@YouTubeSourcePlayback.state = state
                }

                override fun onError(
                    youTubePlayer: YouTubePlayer,
                    error: PlayerConstants.PlayerError,
                ) {
                    endSeconds = NO_TARGET
                    pendingPlay = null
                    request += 1
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
            // 13.0.0부터 Builder가 context를 받는다. origin을 앱 패키지명(https://com.myna)으로
            // 채우기 위해서다. origin을 직접 넣지 않는다 — youtube.com으로 넣으면 다시 거부된다.
            IFramePlayerOptions.Builder(view.context)
                .controls(0)   // 세션 중에는 사용자가 재생을 건드리지 않는다
                .rel(0)
                .build(),
        )
    }

    override fun playSegment(segment: PlaybackSegment, speed: Float, onFinished: () -> Unit) {
        onSegmentFinished = onFinished
        endSeconds = segment.endMs / 1000f

        val start = segment.startMs / 1000f
        val token = ++request
        val action: (YouTubePlayer) -> Unit = { youTubePlayer ->
            youTubePlayer.setPlaybackRate(playbackRateFor(speed))
            youTubePlayer.loadVideo(videoId, start)
            watch(token, start)
        }

        val ready = player
        if (ready == null) pendingPlay = action else action(ready)
    }

    /**
     * 재생이 실제로 시작됐는지 지켜본다.
     *
     * `loadVideo`는 결과를 돌려주지 않는다. 영상이 썸네일에 머문 채 오류도 없이 멈추면
     * 재생 완료 콜백이 영영 오지 않는다. 그래서 몇 초 뒤 상태를 보고, 안 움직이면 한 번 더
     * 밀어 보고, 그래도 안 되면 [onStalled]로 알린다.
     */
    private fun watch(token: Int, start: Float) {
        fun stillWaiting() = token == request && endSeconds > NO_TARGET &&
            state != PlayerConstants.PlayerState.PLAYING

        handler.postDelayed({
            if (stillWaiting() && state != PlayerConstants.PlayerState.BUFFERING) player?.play()
        }, NUDGE_AFTER_MS)
        handler.postDelayed({
            if (stillWaiting() && state != PlayerConstants.PlayerState.BUFFERING) {
                player?.seekTo(start)
                player?.play()
            }
        }, RETRY_AFTER_MS)
        handler.postDelayed({
            // 느린 회선에서 버퍼링 중이면 조금 더 기다린다.
            if (stillWaiting() && state != PlayerConstants.PlayerState.BUFFERING) giveUp(token)
        }, GIVE_UP_AFTER_MS)
        handler.postDelayed({
            if (stillWaiting()) giveUp(token)
        }, GIVE_UP_BUFFERING_AFTER_MS)
    }

    private fun giveUp(token: Int) {
        if (token != request) return
        endSeconds = NO_TARGET
        request += 1
        onStalled("영상이 시작되지 않았습니다 (${stateName(state)}).")
    }

    override fun pause() {
        endSeconds = NO_TARGET
        request += 1
        player?.pause()
    }

    override fun release() {
        request += 1
        handler.removeCallbacksAndMessages(null)
        onSegmentFinished = null
        pendingPlay = null
        player = null
    }

    private fun stateName(state: PlayerConstants.PlayerState): String = when (state) {
        PlayerConstants.PlayerState.VIDEO_CUED -> "대기 화면에서 멈춤"
        PlayerConstants.PlayerState.UNSTARTED -> "시작 전"
        PlayerConstants.PlayerState.BUFFERING -> "불러오는 중"
        PlayerConstants.PlayerState.PAUSED -> "멈춤"
        else -> state.name
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
            "이 영상은 앱 안 재생이 허용되지 않았습니다. 영상 주인이 막아 둔 경우입니다."

        // 앱이 유튜브에 자기를 밝히지 못했다는 뜻이다. 영상 문제가 아니라 앱 문제다.
        PlayerConstants.PlayerError.REQUEST_MISSING_HTTP_REFERER ->
            "유튜브가 이 앱의 재생 요청을 받아 주지 않았습니다. 앱을 최신 버전으로 올려 주세요."

        PlayerConstants.PlayerError.VIDEO_NOT_FOUND ->
            "영상을 찾을 수 없습니다. 비공개로 바뀌었거나 삭제됐을 수 있습니다."

        PlayerConstants.PlayerError.INVALID_PARAMETER_IN_REQUEST ->
            "이 영상은 앱 안에서 재생할 수 없습니다."

        PlayerConstants.PlayerError.HTML_5_PLAYER ->
            "플레이어를 띄우지 못했습니다."

        // 152처럼 라이브러리 오류 표에 없는 코드는 여기로 온다. 원인을 단정하지 않는다.
        PlayerConstants.PlayerError.UNKNOWN ->
            "영상을 재생하지 못했습니다."
    }

    private companion object {
        const val NO_TARGET = -1f
        const val NUDGE_AFTER_MS = 2_500L
        const val RETRY_AFTER_MS = 5_000L
        const val GIVE_UP_AFTER_MS = 9_000L
        const val GIVE_UP_BUFFERING_AFTER_MS = 20_000L
    }
}
