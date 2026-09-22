package com.myna.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File

/** 다운로드 진행 상태. 화면이 그대로 보여 준다. */
internal sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val downloadedBytes: Long, val totalBytes: Long) : DownloadState
    data class Ready(val file: File) : DownloadState
    data class Failed(val message: String) : DownloadState
}

/**
 * APK를 내려받아 설치 화면까지 연결한다.
 *
 * 시스템 [DownloadManager]에 맡긴다. 직접 스트리밍하면 알림, 재시도, 화면 꺼짐 처리를
 * 전부 다시 만들어야 하는데 9MB짜리 파일 하나에 그럴 이유가 없다.
 */
internal class ApkInstaller(private val context: Context) {

    private val downloadManager: DownloadManager?
        get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    /**
     * 내려받는다. 진행 상황을 [onProgress]로 흘려 보내고 완료되면 파일을 돌려준다.
     */
    suspend fun download(
        update: AvailableUpdate,
        onProgress: (DownloadState) -> Unit,
    ): File? {
        val manager = downloadManager ?: run {
            onProgress(DownloadState.Failed("다운로드 관리자를 쓸 수 없습니다."))
            return null
        }

        val fileName = "myna-v${update.version}.apk"
        // 앱 전용 외부 저장소. 권한이 필요 없고, 앱을 지우면 함께 사라진다.
        val target = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (target.exists()) target.delete()

        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("myna v${update.version}")
            .setDescription("새 버전을 내려받는 중")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setMimeType(APK_MIME)

        val downloadId = runCatching { manager.enqueue(request) }.getOrElse { error ->
            onProgress(DownloadState.Failed("다운로드를 시작하지 못했습니다: ${error.message}"))
            return null
        }

        // 완료를 BroadcastReceiver 대신 폴링으로 본다. 화면이 살아 있는 동안만 필요한
        // 정보라 수신자 등록과 해제를 관리할 이유가 없다.
        while (true) {
            val status = manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) return@use null
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val soFarIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                Triple(
                    cursor.getInt(statusIndex),
                    if (soFarIndex >= 0) cursor.getLong(soFarIndex) else 0L,
                    if (totalIndex >= 0) cursor.getLong(totalIndex) else update.sizeBytes,
                )
            }

            if (status == null) {
                onProgress(DownloadState.Failed("다운로드가 취소되었습니다."))
                return null
            }
            val (state, soFar, total) = status
            when (state) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    onProgress(DownloadState.Ready(target))
                    return target
                }
                DownloadManager.STATUS_FAILED -> {
                    onProgress(DownloadState.Failed("다운로드에 실패했습니다."))
                    return null
                }
                else -> onProgress(
                    DownloadState.Running(soFar, if (total > 0) total else update.sizeBytes),
                )
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * 설치 화면을 연다.
     *
     * 사이드로드 설치는 사용자가 "출처를 알 수 없는 앱"을 이 앱에 대해 허용해야 한다.
     * 허용 전이면 설치 시도가 조용히 실패하므로, 먼저 그 설정 화면으로 보낸다.
     */
    fun install(file: File): InstallResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return InstallResult.NeedsPermission(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            InstallResult.Launch(intent)
        }.getOrElse { error ->
            Log.e(TAG, "설치 화면을 열지 못했습니다.", error)
            InstallResult.Failed(error.message ?: "설치를 시작하지 못했습니다.")
        }
    }

    internal sealed interface InstallResult {
        data class Launch(val intent: Intent) : InstallResult
        data class NeedsPermission(val settingsIntent: Intent) : InstallResult
        data class Failed(val message: String) : InstallResult
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val POLL_INTERVAL_MS = 400L
        const val TAG = "ApkInstaller"
    }
}
