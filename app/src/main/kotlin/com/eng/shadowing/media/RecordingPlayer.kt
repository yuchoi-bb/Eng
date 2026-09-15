package com.eng.shadowing.media

import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * 내 녹음 재생 (L1 A/B 비교).
 *
 * 원본 재생기와 분리한 이유는 유튜브 경로 때문이다. IFrame Player는 로컬 파일을 재생할 수
 * 없으므로, 내 녹음은 어느 경로에서든 이쪽이 맡는다. m4a 하나 재생에 [MediaPlayer]면 충분하다.
 */
internal class RecordingPlayer {

    private var player: MediaPlayer? = null

    fun play(file: File, onFinished: () -> Unit) {
        release()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { onFinished() }
                prepare()
                start()
            }
        }.onFailure { error ->
            Log.w(TAG, "녹음을 재생하지 못했습니다.", error)
            release()
            onFinished()
        }
    }

    fun release() {
        runCatching { player?.release() }
        player = null
    }

    private companion object {
        const val TAG = "RecordingPlayer"
    }
}
