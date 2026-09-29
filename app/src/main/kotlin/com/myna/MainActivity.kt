package com.myna

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.myna.core.source.YouTubeUrl
import com.myna.ui.AppRoot

public class MainActivity : ComponentActivity() {

    private var sharedVideoUri by mutableStateOf<Uri?>(null)
    private var sharedYouTubeVideoId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35부터 안드로이드 15는 앱을 상태 바·내비게이션 바 밑까지 그리게 강제한다.
        // 모든 버전에서 같은 방식으로 그리게 하고, 가려지는 만큼은 아래에서 안쪽으로 비킨다.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleShare(intent)

        // 실행할 때마다 지금 버전을 알려 준다. 회전 같은 재생성에는 띄우지 않는다.
        if (savedInstanceState == null) {
            Toast.makeText(this, "myna v${BuildConfig.VERSION_NAME}", Toast.LENGTH_SHORT).show()
        }

        val app = application as MynaApplication
        val repository = app.repository

        setContent {
            MaterialTheme {
                // 배경은 바 밑까지 칠하고, 내용은 시스템 바·키보드를 비켜서 놓는다 —
                // 화면 아래 버튼이 안드로이드 내비게이션 버튼과 겹치지 않게.
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) {
                        AppRoot(
                            repository = repository,
                            apiKeys = app.apiKeys,
                            sharedVideoUri = sharedVideoUri,
                            sharedYouTubeVideoId = sharedYouTubeVideoId,
                            onSharedVideoConsumed = {
                                sharedVideoUri = null
                                sharedYouTubeVideoId = null
                            },
                            onKeepScreenOn = ::keepScreenOn,
                        )
                    }
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

        when {
            // F-2 — 기기에 있는 영상.
            intent.type?.startsWith("video/") == true -> {
                sharedVideoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }

            // F-1 — 유튜브 앱의 공유. 제목과 안내 문구가 섞여 오므로 URL을 찾아낸다.
            intent.type == "text/plain" -> {
                val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
                sharedYouTubeVideoId = YouTubeUrl.extractVideoId(shared)
            }
        }
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
