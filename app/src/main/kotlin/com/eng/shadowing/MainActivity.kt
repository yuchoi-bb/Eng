package com.eng.shadowing

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.eng.shadowing.ui.AppRoot

public class MainActivity : ComponentActivity() {

    private var sharedVideoUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare(intent)

        val repository = (application as EngApplication).repository

        setContent {
            MaterialTheme {
                Surface {
                    AppRoot(
                        repository = repository,
                        sharedVideoUri = sharedVideoUri,
                        onSharedVideoConsumed = { sharedVideoUri = null },
                        onKeepScreenOn = ::keepScreenOn,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /**
     * REQUIREMENTS §9.1 — ACTION_SEND 리시버. S0는 영상 MIME 타입만 받는다.
     *
     * 주의: Kotlin은 블록 주석이 **중첩된다.** 여기에 MIME 와일드카드를 그대로 적으면
     * 슬래시와 별표가 중첩 주석을 열어 버리고, 줄 끝의 닫는 표시가 그 안쪽을 닫는 바람에
     * 바깥 주석이 파일 끝까지 열린 채로 남는다. 실제로 그렇게 깨졌었다.
     */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("video/") != true) return

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        sharedVideoUri = uri
    }

    /** §4.5 — 세션 중에는 화면을 켜 둔다. 손이 자유롭지 않은 무한 루프이기 때문이다. */
    private fun keepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
