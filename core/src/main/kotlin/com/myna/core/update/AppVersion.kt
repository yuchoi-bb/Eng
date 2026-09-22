package com.myna.core.update

/**
 * 버전 비교. 인앱 업데이트 확인이 이 판단 하나에 걸려 있다.
 *
 * 태그(`v0.2.0`)와 앱의 versionName(`0.2.0`)은 표기가 다르므로 둘 다 받아들인다.
 */
public object AppVersion {

    /**
     * [latest]가 [current]보다 새 버전인가.
     *
     * 파싱에 실패하면 **false를 돌려준다.** 알 수 없는 문자열을 새 버전으로 취급하면
     * 사용자에게 설치할 수 없는 업데이트를 계속 권하게 된다.
     */
    public fun isNewer(current: String?, latest: String?): Boolean {
        val a = parse(current) ?: return false
        val b = parse(latest) ?: return false
        return b > a
    }

    public fun parse(raw: String?): Parsed? {
        val text = raw?.trim()?.removePrefix("v")?.removePrefix("V")?.takeIf { it.isNotBlank() } ?: return null
        val core = text.substringBefore('-').substringBefore('+')
        val suffix = text.substringAfter('-', "").takeIf { it.isNotBlank() }

        val parts = core.split('.')
        if (parts.isEmpty() || parts.size > 4) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null

        return Parsed(
            major = numbers.getOrElse(0) { 0 },
            minor = numbers.getOrElse(1) { 0 },
            patch = numbers.getOrElse(2) { 0 },
            preRelease = suffix,
        )
    }

    public data class Parsed(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRelease: String? = null,
    ) : Comparable<Parsed> {

        override fun compareTo(other: Parsed): Int {
            (major - other.major).let { if (it != 0) return it }
            (minor - other.minor).let { if (it != 0) return it }
            (patch - other.patch).let { if (it != 0) return it }
            // 숫자가 같으면 정식 버전이 프리릴리즈보다 위다 — 0.2.0 > 0.2.0-rc1
            return when {
                preRelease == null && other.preRelease == null -> 0
                preRelease == null -> 1
                other.preRelease == null -> -1
                else -> preRelease.compareTo(other.preRelease)
            }
        }
    }

    /**
     * Android `versionCode`로 쓸 단조 증가 정수.
     *
     * 빌드 스크립트와 여기가 같은 규칙을 쓰도록 계산을 한곳에 둔다.
     * 자리수를 넘기면 버전 순서가 뒤집히므로 minor와 patch를 999로 묶는다.
     */
    public fun versionCode(raw: String?): Int {
        val parsed = parse(raw) ?: return 1
        return parsed.major * 1_000_000 +
            parsed.minor.coerceAtMost(999) * 1_000 +
            parsed.patch.coerceAtMost(999)
    }
}
