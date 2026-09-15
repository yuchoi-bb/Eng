package com.eng.shadowing.core.session

/**
 * F-7 쉐도잉 3단계. REQUIREMENTS §7.2.
 *
 * 왕초보가 처음부터 따라 말하면 입이 따라가지 못하므로 문장마다 단계를 나눈다.
 * **3단계를 모두 통과해야 카운트 1회로 인정한다** — 이 규칙이 반복 횟수의 질을 결정한다.
 */
public enum class ShadowingStage {
    /** 1단계 — 듣기만. 자막 표시. */
    LISTEN,

    /** 2단계 — 보면서 따라 말하기. 자막 표시. */
    SHADOW_WITH_TEXT,

    /** 3단계 — 자막 끄고 따라 말하기. 자막 숨김. */
    SHADOW_NO_TEXT,
    ;

    /** §7.2 자막 표시 여부. */
    public val showsSubtitle: Boolean get() = this != SHADOW_NO_TEXT

    /** 1단계는 듣기만이므로 녹음하지 않는다. */
    public val requiresRecording: Boolean get() = this != LISTEN

    public fun next(): ShadowingStage? = when (this) {
        LISTEN -> SHADOW_WITH_TEXT
        SHADOW_WITH_TEXT -> SHADOW_NO_TEXT
        SHADOW_NO_TEXT -> null
    }

    public companion object {
        public val FIRST: ShadowingStage = LISTEN

        /** 1카운트를 구성하는 단계 수. */
        public val COUNT: Int = entries.size
    }
}
