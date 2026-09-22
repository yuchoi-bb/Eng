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
    fun pause()
    fun release()
}
