package com.myna.core.session

/** 소리를 낼 수 있는 환경인가. */
public enum class VoiceMode {
    /** 평소대로 소리 내어 말한다. 녹음하고 채점한다. */
    ALOUD,

    /**
     * 속삭이거나 입모양만 움직인다. **녹음하지 않는다.**
     *
     * 기내·로비·카페처럼 소리를 낼 수 없는 자리에서 쓴다. 마이크를 켜 봐야 주변 소음만
     * 담기고 채점은 전부 오답이 된다. 대신 조음 근육은 실제로 움직이므로 운동 기억은
     * 남는다 — 속으로 되뇌기만 하는 것과 다르다. 그래서 카운트는 그대로 센다.
     */
    WHISPER,
}

/**
 * 한 세션의 조건. 장소와 회선 상태가 단계 구성을 바꾼다.
 */
public data class SessionOptions(
    /**
     * 원본 오디오를 재생할 수 있는가.
     *
     * 유튜브 경로는 회선이 없으면 false다(임베드 재생이므로). 업로드 경로는 원본이
     * 이 기기에 없으면 false다. 현장 메모에서 만든 문장 연습도 원본이 없으므로 false다.
     */
    val sourceAudioAvailable: Boolean = true,
    val voiceMode: VoiceMode = VoiceMode.ALOUD,
) {
    /**
     * 1카운트를 구성하는 단계 순서. REQUIREMENTS §7.2.
     *
     * 원본을 들을 수 없으면 **듣기 단계를 뺀다.** 들려줄 것이 없는데 "듣기"를 세워 두면
     * 사용자는 빈 화면을 넘기게 되고, 카운트의 의미가 흐려진다. 남은 두 단계
     * (보고 말하기 → 자막 끄고 말하기)는 이미 한 번 들어 본 문장의 복습으로 성립한다.
     */
    public val stages: List<ShadowingStage> = buildList {
        if (sourceAudioAvailable) add(ShadowingStage.LISTEN)
        add(ShadowingStage.SHADOW_WITH_TEXT)
        add(ShadowingStage.SHADOW_NO_TEXT)
    }

    /** 녹음과 채점을 할 것인가. */
    public val recordsVoice: Boolean get() = voiceMode == VoiceMode.ALOUD

    /** 이 단계에서 마이크를 켜야 하는가. */
    public fun requiresRecording(stage: ShadowingStage): Boolean =
        stage.isSpeaking && recordsVoice
}
