package com.myna.update

import android.util.Log
import com.myna.core.update.AppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** 설치할 수 있는 새 버전. */
internal data class AvailableUpdate(
    val version: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val notes: String?,
    val releaseUrl: String?,
)

/** 확인 결과. 설정 화면에 그대로 보여 줘서, 업데이트가 안 올 때 이유를 알 수 있게 한다. */
internal sealed interface CheckResult {
    data class Available(val update: AvailableUpdate) : CheckResult
    data object UpToDate : CheckResult
    data class Failed(val reason: String) : CheckResult
}

/**
 * GitHub 릴리즈에서 새 버전을 찾는다.
 *
 * 앱을 사이드로드로 배포하므로 Play Store의 자동 업데이트가 없다. 새 APK가 나온 것을
 * 앱이 스스로 알아차리지 못하면, 사용자는 릴리즈 페이지를 주기적으로 들여다봐야 한다.
 */
internal class UpdateChecker(
    private val repo: String,
    private val currentVersion: String,
) {
    /**
     * 새 버전이 있는지 본다.
     *
     * **실패를 예외로 올리지 않는다.** 업데이트 확인은 부수적인 기능이고, 비행기 모드나
     * GitHub 장애 때문에 학습을 막으면 안 된다. 대신 이유를 [CheckResult.Failed]로 남긴다 —
     * 조용히 삼키면 업데이트가 왜 안 오는지 알 길이 없다.
     */
    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val release = runCatching { fetchLatest() }.getOrElse { error ->
            Log.i(TAG, "업데이트 확인 실패: ${error.message}")
            return@withContext CheckResult.Failed(error.message ?: error.javaClass.simpleName)
        }
        val tag = release.tagName ?: return@withContext CheckResult.Failed("릴리즈에 태그가 없습니다")
        if (release.draft || !AppVersion.isNewer(currentVersion, tag)) {
            return@withContext CheckResult.UpToDate
        }
        val asset = release.apkAsset
        val url = asset?.downloadUrl
        if (asset == null || url == null) {
            Log.w(TAG, "릴리즈 $tag 에 APK가 첨부되어 있지 않습니다.")
            return@withContext CheckResult.Failed("$tag 에 APK가 없습니다")
        }
        CheckResult.Available(
            AvailableUpdate(
                version = tag.removePrefix("v"),
                downloadUrl = url,
                sizeBytes = asset.size,
                notes = release.body?.takeIf { it.isNotBlank() },
                releaseUrl = release.htmlUrl,
            ),
        )
    }

    private fun fetchLatest(): GitHubRelease {
        val connection = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection()
            as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("GitHub이 ${connection.responseCode}를 돌려주었습니다")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return json.decodeFromString(GitHubRelease.serializer(), body)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        // 확인 시점 판단은 core의 UpdatePolicy가 한다 — 앱을 켤 때마다 확인한다.

        private const val TIMEOUT_MS = 10_000
        private const val TAG = "UpdateChecker"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
