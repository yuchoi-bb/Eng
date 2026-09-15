package com.eng.shadowing.core.transcript

/**
 * 검증 이전의 전사 응답. **모든 필드가 nullable이고 enum이 문자열이다.**
 *
 * TRANSCRIPTION_SCHEMA §4.1 — "LLM 출력을 신뢰하지 않는다." 파싱 단계에서 타입을 좁히면
 * 잘못된 enum 값 하나에 응답 전체가 예외로 날아간다. 원본을 그대로 받아
 * [TranscriptValidator]가 항목별로 폴백하도록 한다.
 */
public data class RawTranscript(
    val schemaVersion: Int? = null,
    val source: String? = null,
    val sourceRef: String? = null,
    val language: String? = null,
    val type: String? = null,
    val cefr: String? = null,
    val timestampUnit: String? = null,
    val speechStartMs: Int? = null,
    val speechEndMs: Int? = null,
    val sentences: List<RawSentence> = emptyList(),
    val expressions: List<RawExpression> = emptyList(),
    val warnings: List<String> = emptyList(),
)

public data class RawSentence(
    val index: Int? = null,
    val text: String? = null,
    val translationKo: String? = null,
    val transliterationKo: String? = null,
    val startMs: Int? = null,
    val endMs: Int? = null,
    val keywords: List<String> = emptyList(),
    val breathGroups: List<RawBreathGroup> = emptyList(),
)

public data class RawBreathGroup(
    val text: String? = null,
    val startMs: Int? = null,
    val endMs: Int? = null,
)

public data class RawExpression(
    val text: String? = null,
    val meaningKo: String? = null,
)
