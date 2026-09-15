package com.eng.shadowing.core.model

import kotlinx.serialization.Serializable
/**
 * 전사 응답의 도메인 표현. 필드 구성은 TRANSCRIPTION_SCHEMA.md(schemaVersion 1) §2를 따른다.
 *
 * 스키마에 있는 것만 담는다. `speechSec`/`wpm`/`suggestedReps` 같은 산술 결과는
 * 여기 없다 — TRANSCRIPTION_SCHEMA §1.2에 따라 앱이 계산하며, [VideoPlan]이 들고 있다.
 */
@Serializable
public data class Transcript(
    val schemaVersion: Int,
    val source: VideoSource,
    val sourceRef: String,
    val language: String,
    val type: VideoType,
    val cefr: Cefr,
    val timestampUnit: TimestampUnit,
    val speechStartMs: Int,
    val speechEndMs: Int,
    val sentences: List<Sentence>,
    val expressions: List<Expression> = emptyList(),
    val warnings: List<TranscriptWarning> = emptyList(),
)

@Serializable
public data class Sentence(
    val index: Int,
    val text: String,
    val translationKo: String,
    val startMs: Int,
    val endMs: Int,
    val keywords: List<String>,
    val transliterationKo: String? = null,
    val breathGroups: List<BreathGroup> = emptyList(),
) {
    /** 이 문장의 발화 길이. 구간 반복과 발화 시간 집계의 기준. */
    val durationMs: Int get() = endMs - startMs
}

@Serializable
public data class BreathGroup(
    val text: String,
    val startMs: Int,
    val endMs: Int,
)

/** F-8 표현 은행(v2). v1에서도 응답에 포함되므로 적재만 한다 — TRANSCRIPTION_SCHEMA §3.3. */
@Serializable
public data class Expression(
    val text: String,
    val meaningKo: String,
)

@Serializable
public enum class VideoSource { YOUTUBE, UPLOAD }

/** REQUIREMENTS §4.4 — 영상 유형별로 반복 방식이 달라진다. */
@Serializable
public enum class VideoType { DRILL, SINGLE, DIALOGUE }

@Serializable
public enum class Cefr { A1, A2, B1, B2, C1, C2 }

/**
 * 타임스탬프 정밀도. 유튜브 경로는 초 단위라 문장 경계가 잘릴 수 있어
 * 재생 시 패딩이 필요하다 — TRANSCRIPTION_SCHEMA §1.1.
 */
@Serializable
public enum class TimestampUnit { SECOND, MILLISECOND }

/** TRANSCRIPTION_SCHEMA §3.4. */
@Serializable
public enum class TranscriptWarning { MUSIC_HEAVY, MULTIPLE_SPEAKERS, FAST_SPEECH, LOW_CONFIDENCE }
