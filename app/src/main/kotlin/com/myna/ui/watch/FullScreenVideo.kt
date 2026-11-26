package com.myna.ui.watch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 영상 전체 화면. 상태 바와 내비게이션 바를 숨기고 영상만 꽉 채운다.
 *
 * 쇼츠는 세로라 그대로 두면 되고, 가로 영상은 [돌리기]로 가로 화면을 고정한다. 닫으면 원래대로
 * 돌린다. 보던 위치는 [onPosition]으로 넘겨 작은 화면에서 이어 본다.
 */
@Composable
internal fun FullScreenVideo(
    youTubeVideoId: String?,
    videoUri: Uri?,
    startMs: Long,
    onPosition: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val activity = LocalContext.current.findActivity()
    var landscape by remember { mutableStateOf(false) }

    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        WatchPlayer(
            youTubeVideoId = youTubeVideoId,
            videoUri = videoUri,
            startMs = startMs,
            autoplay = true,
            onPosition = onPosition,
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            Modifier.fillMaxWidth().padding(8.dp).align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayButton("✕ 닫기", onClose)
            Box(Modifier.weight(1f))
            OverlayButton(if (landscape) "세로로" else "가로로") {
                landscape = !landscape
                activity?.requestedOrientation = if (landscape) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
        }
    }
}

@Composable
private fun OverlayButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50)),
    ) {
        Text(label, color = Color.White)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
