package com.myna.update

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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
        // 설치 권한을 켜러 다녀온 경우처럼 이미 다 받아 둔 파일이면 다시 받지 않는다.
        if (target.exists() && update.sizeBytes > 0 && target.length() == update.sizeBytes) {
            onProgress(DownloadState.Ready(target))
            return target
        }
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
     * 설치한다.
     *
     * [PackageInstaller] 세션으로 넘긴다. 파일을 여는 방식(ACTION_VIEW)은 매번 설치 확인
     * 화면을 띄우지만, 세션 방식은 **이 앱이 자기 자신을 설치한 적이 있으면** 안드로이드 12부터
     * 확인 없이 바로 설치된다. 처음 한 번(브라우저로 설치한 앱)은 확인 화면이 뜬다 —
     * [InstallResultReceiver]가 그 화면을 띄운다.
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
            commitSession(file)
            InstallResult.Committed
        }.getOrElse { error ->
            // 세션을 못 쓰는 기기라면 예전처럼 파일을 설치 화면에 넘긴다.
            Log.w(TAG, "설치 세션을 쓰지 못해 설치 화면으로 넘깁니다.", error)
            viewIntent(file)
        }
    }

    private fun commitSession(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // 자기 자신의 업데이트는 확인 없이 설치해 달라고 요청한다. 조건이 안 맞으면
                // (처음 한 번 등) 시스템이 알아서 확인 화면을 요구한다.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                val callback = Intent(context, InstallResultReceiver::class.java)
                    .setPackage(context.packageName)
                // 시스템이 결과를 extras에 채워 넣으므로 mutable이어야 한다. 대상은 명시적이다.
                val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutable
                val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                session.commit(pending.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    private fun viewIntent(file: File): InstallResult = runCatching {
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

    internal sealed interface InstallResult {
        /** 설치 세션에 넘겼다. 결과는 [InstallResultReceiver]로 온다. */
        data object Committed : InstallResult
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
