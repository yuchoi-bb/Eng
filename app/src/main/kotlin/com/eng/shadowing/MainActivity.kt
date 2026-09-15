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

    /** REQUIREMENTS §9.1 — ACTION_SEND 리시버. S0는 video/*만 받는다. */
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
