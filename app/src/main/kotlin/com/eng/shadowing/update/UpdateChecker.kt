package com.eng.shadowing.update

import android.util.Log
import com.eng.shadowing.core.update.AppVersion
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
     * 새 버전이 있으면 돌려주고, 없거나 확인에 실패하면 null.
     *
     * **실패를 예외로 올리지 않는다.** 업데이트 확인은 부수적인 기능이고, 비행기 모드나
     * GitHub 장애 때문에 학습을 막으면 안 된다.
     */
    suspend fun check(): AvailableUpdate? = withContext(Dispatchers.IO) {
        runCatching { fetchLatest() }
            .onFailure { Log.i(TAG, "업데이트 확인을 건너뜁니다: ${it.message}") }
            .getOrNull()
            ?.let { release ->
                val tag = release.tagName ?: return@let null
                if (release.draft) return@let null
                if (!AppVersion.isNewer(currentVersion, tag)) return@let null

                val asset = release.apkAsset ?: run {
                    Log.w(TAG, "릴리즈 $tag 에 APK가 첨부되어 있지 않습니다.")
                    return@let null
                }
                val url = asset.downloadUrl ?: return@let null

                AvailableUpdate(
                    version = tag.removePrefix("v"),
                    downloadUrl = url,
                    sizeBytes = asset.size,
                    notes = release.body?.takeIf { it.isNotBlank() },
                    releaseUrl = release.htmlUrl,
                )
            }
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
        /** 확인 간격. 개인용 앱에 하루 네 번이면 충분하고, 비인증 레이트리밋과도 무관해진다. */
        const val CHECK_INTERVAL_MS: Long = 6 * 60 * 60 * 1000

        private const val TIMEOUT_MS = 10_000
        private const val TAG = "UpdateChecker"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
