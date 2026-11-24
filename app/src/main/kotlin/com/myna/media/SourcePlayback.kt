package com.myna.media

import com.myna.core.playback.PlaybackSegment

/**
 * 원본 영상의 구간 재생.
 *
 * 두 소스가 완전히 다른 재생기를 쓴다 — 업로드는 ExoPlayer로 로컬 파일을, 유튜브는
 * 공식 IFrame Player로 임베드를 재생한다(§F-1. 앱 내 다운로드는 ToS 위반이므로 하지 않는다).
 * 세션 로직이 그 차이를 몰라도 되도록 여기서 가린다.
 */
internal interface SourcePlayback {
    fun playSegment(segment: PlaybackSegment, speed: Float, onFinished: () -> Unit)

    /**
     * 누르고 있는 동안 듣기용 — [segment]의 시작부터 곧바로 재생한다. 이미 불러온 영상이면
     * 다시 불러오지 않고 그 자리로 건너뛴다. 떼었다 다시 누를 때마다 영상을 새로 불러오면
     * 소리가 늦게 나온다.
     */
    fun playFrom(segment: PlaybackSegment, speed: Float, onFinished: () -> Unit) =
        playSegment(segment, speed, onFinished)

    /** 지금 재생 위치(영상 시작부터의 밀리초). 모르면 null. 글자를 소리에 맞춰 칠하는 데 쓴다. */
    fun currentPositionMs(): Long? = null

    fun pause()
    fun release()
}
