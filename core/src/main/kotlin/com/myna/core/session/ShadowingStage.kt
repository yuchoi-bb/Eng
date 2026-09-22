package com.myna.core.session

/**
 * F-7 쉐도잉 단계. REQUIREMENTS §7.2.
 *
 * 왕초보가 처음부터 따라 말하면 입이 따라가지 못하므로 문장마다 단계를 나눈다.
 * **한 문장의 모든 단계를 통과해야 카운트 1회로 인정한다** — 이 규칙이 반복 횟수의 질을 결정한다.
 *
 * 단계의 개수는 고정이 아니다. 원본을 들을 수 없는 자리에서는 듣기 단계가 빠진다 —
 * [SessionOptions.stages] 참조.
 */
public enum class ShadowingStage {
    /** 듣기만. 자막 표시. */
    LISTEN,

    /** 보면서 따라 말하기. 자막 표시. */
    SHADOW_WITH_TEXT,

    /** 자막 끄고 따라 말하기. 자막 숨김. */
    SHADOW_NO_TEXT,
    ;

    /** §7.2 자막 표시 여부. */
    public val showsSubtitle: Boolean get() = this != SHADOW_NO_TEXT

    /** 입을 움직이는 단계인가. 듣기 단계만 아니다. */
    public val isSpeaking: Boolean get() = this != LISTEN
}
