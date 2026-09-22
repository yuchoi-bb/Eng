package com.myna.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 인앱 업데이트 판단. 여기가 틀리면 설치할 수 없는 업데이트를 계속 권하게 된다. */
class AppVersionTest {

    @Test
    fun `태그 표기와 versionName 표기를 모두 받는다`() {
        assertTrue(AppVersion.isNewer(current = "0.1.0", latest = "v0.2.0"))
        assertTrue(AppVersion.isNewer(current = "v0.1.0", latest = "0.2.0"))
    }

    @Test
    fun `같은 버전은 업데이트가 아니다`() {
        assertFalse(AppVersion.isNewer("0.1.0", "v0.1.0"))
    }

    @Test
    fun `낮은 버전은 업데이트가 아니다`() {
        assertFalse(AppVersion.isNewer("0.2.0", "v0.1.9"))
    }

    @Test
    fun `자리별로 비교한다`() {
        assertTrue(AppVersion.isNewer("0.9.0", "0.10.0"))   // 문자열 비교였다면 틀린다
        assertTrue(AppVersion.isNewer("1.0.0", "1.0.1"))
        assertTrue(AppVersion.isNewer("1.9.9", "2.0.0"))
    }

    @Test
    fun `정식 버전이 프리릴리즈보다 위다`() {
        assertTrue(AppVersion.isNewer("0.2.0-rc1", "0.2.0"))
        assertFalse(AppVersion.isNewer("0.2.0", "0.2.0-rc1"))
    }

    @Test
    fun `파싱에 실패하면 업데이트로 보지 않는다`() {
        // 알 수 없는 문자열을 새 버전으로 취급하면 설치 불가능한 업데이트를 계속 권한다.
        assertFalse(AppVersion.isNewer("0.1.0", "nightly"))
        assertFalse(AppVersion.isNewer("0.1.0", null))
        assertFalse(AppVersion.isNewer(null, "0.2.0"))
        assertFalse(AppVersion.isNewer("0.1.0", "v1.2.3.4.5"))
    }

    @Test
    fun `자리가 모자라면 0으로 채운다`() {
        assertEquals(AppVersion.Parsed(1, 0, 0), AppVersion.parse("1"))
        assertEquals(AppVersion.Parsed(1, 2, 0), AppVersion.parse("1.2"))
        assertNull(AppVersion.parse(""))
    }

    @Test
    fun `versionCode는 버전 순서를 지킨다`() {
        assertTrue(AppVersion.versionCode("0.2.0") > AppVersion.versionCode("0.1.9"))
        assertTrue(AppVersion.versionCode("0.10.0") > AppVersion.versionCode("0.9.0"))
        assertTrue(AppVersion.versionCode("1.0.0") > AppVersion.versionCode("0.999.999"))
        assertEquals(1, AppVersion.versionCode("이상한값"))
    }
}
