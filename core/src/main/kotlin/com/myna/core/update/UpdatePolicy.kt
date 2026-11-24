package com.myna.core.update

/**
 * 언제 새 버전을 확인할 것인가.
 *
 * **앱을 켤 때마다 확인한다.** 사이드로드 배포라 Play Store가 대신 알려 주지 않으므로,
 * 앱이 스스로 묻지 않으면 사용자는 릴리즈 페이지를 들여다봐야 한다. 처음에는 6시간
 * 간격을 뒀는데, 그 사이에 나온 버전들을 앱이 한 번도 알리지 못했다.
 *
 * 비용은 실행당 요청 한 번, 1KB 남짓이다. 비인증 GitHub API는 IP당 시간 60회까지
 * 허용하므로 사람이 앱을 켜는 빈도로는 닿지 않는다. 실패하면 조용히 넘어간다.
 */
public object UpdatePolicy {

    /**
     * 연속 확인 사이의 최소 간격.
     *
     * 기기에 따라 화면 복귀 이벤트가 짧은 사이에 여러 번 들어온다. 그때마다 요청을 보내면
     * 아무 소득 없이 왕복만 쌓인다. 1분이면 사람이 켜고 끄는 리듬을 막지 않으면서
     * 그 반복은 걸러 낸다.
     */
    public const val MIN_INTERVAL_MS: Long = 60_000

    public fun shouldCheck(lastCheckedEpochMs: Long?, nowEpochMs: Long): Boolean {
        val last = lastCheckedEpochMs ?: return true
        // 기기 시간을 되돌리면 last가 미래가 된다. 그때 영영 확인하지 않는 상태로 굳지 않게 한다.
        if (nowEpochMs < last) return true
        return nowEpochMs - last >= MIN_INTERVAL_MS
    }
}
