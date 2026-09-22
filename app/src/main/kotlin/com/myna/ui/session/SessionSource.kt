package com.myna.ui.session

import android.net.Uri

/**
 * 세션이 재생할 원본.
 *
 * 전사 스키마의 `source`/`sourceRef`에서 그대로 나온다 — 유튜브는 `sourceRef`가 곧 영상 ID다
 * (TRANSCRIPTION_SCHEMA §3.1).
 */
internal sealed interface SessionSource {
    /** F-2 — 기기에 있는 영상. URI는 이 기기에서만 유효하다(§10.1). */
    data class Upload(val uri: Uri) : SessionSource

    /** F-1 — 유튜브 임베드. 앱은 URL과 텍스트만 보관한다(§F-1). */
    data class YouTube(val videoId: String) : SessionSource
}
