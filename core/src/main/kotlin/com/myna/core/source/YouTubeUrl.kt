package com.myna.core.source

/**
 * 공유받은 텍스트에서 유튜브 영상 ID를 뽑아낸다. REQUIREMENTS §F-1.
 *
 * 유튜브 앱의 "공유"는 URL만 보내지 않는다. 제목과 안내 문구가 섞인 한 덩어리 텍스트를
 * 보내는 경우가 흔하므로, 문자열 전체에서 URL을 찾아낸다.
 */
public object YouTubeUrl {

    /** 유튜브 영상 ID는 11자다. */
    private const val ID_LENGTH = 11

    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{$ID_LENGTH}")

    /**
     * 유튜브 호스트로 인정하는 목록. `youtube.com`으로 끝나는지 검사하지 않고
     * 정확히 대조한다 — `myyoutube.com` 같은 호스트를 받아들이면 안 된다.
     */
    private val HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com",
        "music.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com",
        "youtu.be", "www.youtu.be",
    )

    /**
     * 영상 ID를 뽑는다. 유튜브 URL이 아니거나 ID를 찾지 못하면 null.
     *
     * 지원하는 형태:
     * - `youtube.com/shorts/<id>`  (쇼츠 — 기본 소재)
     * - `youtu.be/<id>`            (공유 단축 링크)
     * - `youtube.com/watch?v=<id>`
     * - `youtube.com/embed/<id>`, `youtube.com/live/<id>`, `youtube.com/v/<id>`
     */
    public fun extractVideoId(text: String?): String? {
        val candidate = text?.takeIf { it.isNotBlank() } ?: return null
        for (raw in URL_IN_TEXT.findAll(candidate).map { it.value }) {
            videoIdFrom(raw)?.let { return it }
        }
        return null
    }

    public fun isYouTubeLink(text: String?): Boolean = extractVideoId(text) != null

    private fun videoIdFrom(rawUrl: String): String? {
        val withScheme = if (rawUrl.startsWith("http", ignoreCase = true)) rawUrl else "https://$rawUrl"
        val host = hostOf(withScheme)?.lowercase() ?: return null
        if (host !in HOSTS) return null

        val afterHost = withScheme.substringAfter(host, "")
        val path = afterHost.substringBefore('?').substringBefore('#').trim('/')
        val query = afterHost.substringAfter('?', "").substringBefore('#')

        // youtu.be/<id> — 경로 첫 조각이 곧 ID다.
        if (host.endsWith("youtu.be")) {
            return path.substringBefore('/').validId()
        }

        // watch?v=<id>
        queryValue(query, "v")?.validId()?.let { return it }

        // /shorts/<id>, /embed/<id>, /live/<id>, /v/<id>
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.size >= 2 && segments[0].lowercase() in PATH_PREFIXES) {
            return segments[1].validId()
        }
        return null
    }

    private fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        return afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfter('@')       // user:pass@host 형태를 호스트로 오인하지 않는다
            .substringBefore(':')      // 포트 제거
            .takeIf { it.isNotBlank() }
    }

    private fun queryValue(query: String, key: String): String? =
        query.split('&')
            .firstOrNull { it.startsWith("$key=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }

    private fun String.validId(): String? = takeIf { ID_PATTERN.matches(it) }

    private val PATH_PREFIXES = setOf("shorts", "embed", "live", "v")

    /** 텍스트 안에 섞인 URL을 찾는다. 공유 문구에 제목이 함께 오는 경우가 흔하다. */
    private val URL_IN_TEXT = Regex("""(?:https?://)?[A-Za-z0-9._~%-]+\.[A-Za-z]{2,}(?:/[^\s<>"']*)?""")
}
