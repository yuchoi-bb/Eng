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
    /**
     * 손대지 않고 듣고 따라 하기. 말할 차례가 되면 녹음하지 않고, 들려준 길이만큼 기다렸다가
     * 저절로 다음으로 넘어간다. 걷거나 운전하거나 설거지하며 화면을 못 볼 때 쓴다.
     */
    val handsFree: Boolean = false,
    /** 손대지 않는 모드에서 따라 말할 시간 = 들려준 길이 × 이 배수. 왕초보는 늘려 쓴다. */
    val followFactor: Float = 1f,
) {
    /**
     * 처음 듣는 회차의 단계 순서. REQUIREMENTS §7.2.
     *
     * 원본을 들을 수 없으면 **듣기 단계를 뺀다.** 들려줄 것이 없는데 "듣기"를 세워 두면
     * 사용자는 빈 화면을 넘기게 되고, 카운트의 의미가 흐려진다. 남은 두 단계
     * (보고 말하기 → 자막 끄고 말하기)는 이미 한 번 들어 본 문장의 복습으로 성립한다.
     */
    public val stages: List<ShadowingStage> = stagesFor(repIndex = 0, memorized = false)

    /**
     * 한 걸음(문장 1개의 한 회차)을 구성하는 단계. 1카운트 = 이 단계를 모두 끝내는 것.
     *
     * - **외운 문장**: 자막 없이 바로 말하기 하나. 듣는 시간을 빼고 말하는 횟수를 늘린다.
     * - **처음 듣는 회차**(repIndex 0): 듣기 → 보고 말하기 → 자막 끄고 말하기.
     * - **그다음 회차**: 듣기만 하는 단계를 뺀다. 2·3단계도 말하기 직전에 원본을 들려주므로
     *   따로 "듣기만"을 두면 같은 소리를 한 회차에 세 번 듣게 된다.
     *
     * @param sourceAudioAvailable 세션 도중 원본 재생이 막히면 false로 다시 묻는다.
     */
    public fun stagesFor(
        repIndex: Int,
        memorized: Boolean,
        sourceAudioAvailable: Boolean = this.sourceAudioAvailable,
    ): List<ShadowingStage> = when {
        memorized -> listOf(ShadowingStage.RECALL)
        repIndex == 0 && sourceAudioAvailable -> listOf(
            ShadowingStage.LISTEN,
            ShadowingStage.SHADOW_WITH_TEXT,
            ShadowingStage.SHADOW_NO_TEXT,
        )
        else -> listOf(ShadowingStage.SHADOW_WITH_TEXT, ShadowingStage.SHADOW_NO_TEXT)
    }

    /** 녹음과 채점을 할 것인가. 손대지 않는 모드는 "말하기 끝"을 누를 사람이 없으므로 녹음하지 않는다. */
    public val recordsVoice: Boolean get() = voiceMode == VoiceMode.ALOUD && !handsFree

    /**
     * 손대지 않는 모드에서 말할 차례에 기다리는 시간.
     *
     * 들려준 구간이 배속으로 늘어난 만큼 따라 말하는 데도 걸린다. 거기에 말을 시작하기까지의
     * 틈([FOLLOW_LEAD_MS])을 더한다 — 소리가 끝나자마자 재기 시작하면 첫 단어를 놓친다.
     */
    public fun followPauseMs(segmentMs: Int, playbackRate: Float): Long {
        val rate = playbackRate.takeIf { it > 0f } ?: 1f
        val spoken = segmentMs.coerceAtLeast(0) / rate * followFactor.coerceIn(0.5f, 4f)
        return spoken.toLong() + FOLLOW_LEAD_MS
    }

    /** 이 단계에서 마이크를 켜야 하는가. */
    public fun requiresRecording(stage: ShadowingStage): Boolean =
        stage.isSpeaking && recordsVoice

    public companion object {
        /**
         * 외운 문장은 이 회차마다 한 번, 말한 **뒤에** 원본을 들려준다.
         *
         * 왕초보가 원본을 전혀 듣지 않고 반복하면 틀린 발음이 굳는다. 먼저 말하고 나중에
         * 들으면 기억에서 꺼내는 연습은 살리면서 어긋난 곳을 스스로 알아챌 수 있다.
         */
        public const val RECALL_CHECK_INTERVAL: Int = 3

        /** 소리가 끝나고 말을 시작하기까지의 틈. */
        public const val FOLLOW_LEAD_MS: Long = 700
    }
}
