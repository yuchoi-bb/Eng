package com.myna.core.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 앱을 켤 때마다 확인하되, 같은 순간에 몰린 확인은 거른다. */
class UpdatePolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `한 번도 확인한 적 없으면 확인한다`() {
        assertTrue(UpdatePolicy.shouldCheck(null, now))
    }

    @Test
    fun `앱을 다시 켜면 확인한다`() {
        // 6시간 간격이던 때는 그 사이에 나온 버전을 앱이 한 번도 알리지 못했다.
        val fiveMinutesAgo = now - 5 * 60 * 1000
        assertTrue(UpdatePolicy.shouldCheck(fiveMinutesAgo, now))
    }

    @Test
    fun `1분 안에 다시 들어오면 건너뛴다`() {
        // 기기에 따라 화면 복귀 이벤트가 짧은 사이에 여러 번 들어온다.
        assertFalse(UpdatePolicy.shouldCheck(now - 10_000, now))
        assertFalse(UpdatePolicy.shouldCheck(now, now))
    }

    @Test
    fun `정확히 1분이 지나면 확인한다`() {
        assertTrue(UpdatePolicy.shouldCheck(now - UpdatePolicy.MIN_INTERVAL_MS, now))
    }

    @Test
    fun `기기 시간을 되돌려도 굳지 않는다`() {
        // last가 미래면 뺄셈이 음수가 되어 영영 확인하지 않는 상태가 된다.
        assertTrue(UpdatePolicy.shouldCheck(now + 60L * 60 * 1000, now))
    }
}
