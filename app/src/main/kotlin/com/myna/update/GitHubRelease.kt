package com.myna.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GitHub Releases API 응답 중 필요한 부분만.
 *
 * 저장소가 공개라 토큰 없이 조회된다. 비인증 요청은 IP당 시간 60회로 제한되는데,
 * 앱을 켤 때마다 한 번씩만 물어보므로 사람이 켜는 빈도로는 닿지 않는다.
 */
@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
) {
    /** 첨부된 APK. 릴리즈 노트나 소스 zip이 아니라 실제 설치 파일만 고른다. */
    val apkAsset: GitHubAsset?
        get() = assets.firstOrNull { it.name?.endsWith(".apk", ignoreCase = true) == true }
}

@Serializable
internal data class GitHubAsset(
    val name: String? = null,
    @SerialName("browser_download_url") val downloadUrl: String? = null,
    val size: Long = 0,
)
