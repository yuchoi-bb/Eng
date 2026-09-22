package com.myna.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.myna.BuildConfig
import com.myna.update.ApkInstaller
import com.myna.update.AvailableUpdate
import com.myna.update.DownloadState
import com.myna.update.UpdateChecker
import kotlinx.coroutines.launch

/**
 * 새 버전이 나왔는지 보고, 있으면 받아서 설치할지 묻는다.
 *
 * 사이드로드 배포라 Play Store의 자동 업데이트가 없다. 앱이 스스로 알리지 않으면
 * 사용자가 릴리즈 페이지를 들여다봐야 새 빌드를 안다.
 *
 * 화면을 가로막지 않는다 — 확인에 실패하거나 네트워크가 없으면 조용히 넘어간다.
 */
@Composable
internal fun UpdateGate(
    lastCheckedEpochMs: Long?,
    skippedVersion: String?,
    onChecked: (Long) -> Unit,
    onSkipVersion: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var update by remember { mutableStateOf<AvailableUpdate?>(null) }
    var download by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        val due = lastCheckedEpochMs == null ||
            now - lastCheckedEpochMs >= UpdateChecker.CHECK_INTERVAL_MS
        if (!due) return@LaunchedEffect

        onChecked(now)
        val found = UpdateChecker(
            repo = BuildConfig.UPDATE_REPO,
            currentVersion = BuildConfig.VERSION_NAME,
        ).check()

        // 건너뛴 버전은 다시 묻지 않는다. 그 다음 버전이 나오면 다시 묻는다.
        if (found != null && found.version != skippedVersion) update = found
    }

    val pending = update ?: return
    val busy = download is DownloadState.Running

    AlertDialog(
        onDismissRequest = { if (!busy) update = null },
        title = { Text("새 버전 v${pending.version}") },
        text = { Text(bodyText(pending, download, message)) },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        val installer = ApkInstaller(context)
                        val file = installer.download(pending) { download = it } ?: return@launch
                        when (val result = installer.install(file)) {
                            is ApkInstaller.InstallResult.Launch -> {
                                context.startActivity(result.intent)
                                update = null
                            }
                            is ApkInstaller.InstallResult.NeedsPermission -> {
                                // 허용 전에는 설치가 조용히 실패한다. 설정으로 보낸 뒤 다시 누르게 한다.
                                message = "이 앱의 설치 권한을 허용해 주세요. 돌아와서 다시 누르면 됩니다."
                                download = DownloadState.Idle
                                context.startActivity(result.settingsIntent)
                            }
                            is ApkInstaller.InstallResult.Failed -> {
                                download = DownloadState.Failed(result.message)
                                openReleasePage(context, pending)
                            }
                        }
                    }
                },
            ) {
                Text("받아서 설치")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    onSkipVersion(pending.version)
                    update = null
                },
            ) {
                Text("이 버전 건너뛰기")
            }
        },
    )
}

private fun bodyText(
    update: AvailableUpdate,
    download: DownloadState,
    message: String?,
): String = when (download) {
    is DownloadState.Running -> "내려받는 중… ${percent(download)}"
    is DownloadState.Ready -> "설치 화면을 엽니다."
    is DownloadState.Failed -> download.message
    DownloadState.Idle -> buildString {
        append("지금 쓰는 버전은 v${BuildConfig.VERSION_NAME}입니다.\n")
        append("${sizeText(update.sizeBytes)}를 받아서 설치할까요?")
        message?.let { append("\n\n$it") }
    }
}

private fun openReleasePage(context: Context, update: AvailableUpdate) {
    val url = update.releaseUrl ?: return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun percent(state: DownloadState.Running): String =
    if (state.totalBytes <= 0) "" else "${state.downloadedBytes * 100 / state.totalBytes}%"

private fun sizeText(bytes: Long): String =
    if (bytes <= 0) "새 버전" else "약 ${bytes / (1024 * 1024)}MB"
