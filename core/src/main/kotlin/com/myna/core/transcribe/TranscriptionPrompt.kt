package com.myna.core.transcribe

/**
 * 전사 요청 프롬프트. TRANSCRIPTION_SCHEMA §5.
 *
 * **짧게 유지한다.** 구조화 출력(responseSchema)이 형식을 강제하므로, 프롬프트가 할 일은
 * 형식 설명이 아니라 *판단 기준*을 알려 주는 것뿐이다. 형식을 말로 다시 설명하면 모델이
 * 스키마와 프롬프트 중 어느 쪽을 따를지 흔들린다.
 */
public object TranscriptionPrompt {

    public val forVideo: String = """
        You are transcribing a short English video for a Korean learner at CEFR A1-A2 level.

        Rules:
        1. Transcribe only what is actually spoken, in order. Do not invent or complete sentences.
        2. Split into short sentences a beginner can repeat in one breath.
        3. translationKo: natural spoken Korean, not literal word-for-word. Write it the way a
           Korean person would actually say the same thing.
        4. transliterationKo: how the sentence sounds, written in Hangul. Reflect linking and
           reduction as they are actually heard, not how the words are spelled.
        5. keywords: 2-4 content words or chunks that MUST appear verbatim in that sentence's
           text. Never a bare article or preposition. These are the grading targets.
        6. type: DRILL if the video repeats the same phrase for practice, SINGLE if it is one
           long utterance spoken once, DIALOGUE if two or more turns or several distinct
           sentences.
        7. breathGroups: required only when type is SINGLE. Split the long sentence into
           1.5-3 second chunks at natural pauses.
        8. cefr: difficulty of the language in the video, not of the topic.
        9. warnings: add MUSIC_HEAVY when music or singing dominates, FAST_SPEECH when the
           delivery is too fast for a beginner, MULTIPLE_SPEAKERS for three or more speakers,
           LOW_CONFIDENCE when the audio is unclear.
        10. Timestamps in milliseconds from the start of the video.

        Do not compute, summarise, or give advice. Fill the schema fields only.
    """.trimIndent()

    /**
     * 기기에서 고른 영상용. 소리가 기준이고, 화면의 자막은 철자와 문장 경계를 돕는 보조다.
     *
     * 자막은 편집 과정에서 말과 어긋나거나 요약되는 경우가 많다. 그래서 자막을 받아 적지
     * 말고 들리는 말을 적게 한다. 한국어 자막이 입혀진 영상은 해석의 참고로만 쓴다.
     */
    public val forUploadedVideo: String = forVideo + "\n\n" + """
        This video file may have subtitles or captions burned into the picture.
        - The audio is the source of truth. Transcribe what is spoken, not what is written.
        - Use English on-screen captions only to get spelling, names, and sentence boundaries
          right when the audio is unclear.
        - Timestamps must follow when the words are spoken, not when captions appear.
        - Ignore on-screen text that is not spoken (titles, watermarks, usernames).
        - If Korean subtitles are shown, you may use them as a hint for translationKo, but keep
          translationKo natural spoken Korean for what is actually said.
    """.trimIndent()
}
