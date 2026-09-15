package com.eng.shadowing.core.model

/** REQUIREMENTS §8 왕초보 튜닝 기본값. */
public data class UserSettings(
    val dailyTargetSec: Int = DEFAULT_DAILY_TARGET_SEC,
    val defaultAutoReps: Boolean = true,
    val defaultManualReps: Int = 30,
    val playbackRate: Float = 0.75f,
    val showTransliterationKo: Boolean = false,
) {
    public companion object {
        /** §8 — 10분은 초보자 좌절 구간이므로 5분에서 시작한다. */
        public const val DEFAULT_DAILY_TARGET_SEC: Int = 300

        /** §4.7 온보딩 선택지. */
        public val ONBOARDING_TARGETS_SEC: List<Int> = listOf(300, 600, 900)
    }
}
