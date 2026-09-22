package com.myna.core.notes

import com.myna.core.model.TimestampUnit
import com.myna.core.model.VideoPlan
import com.myna.core.model.VideoPlanId
import com.myna.core.model.VideoSource
import com.myna.core.session.RepsCalculator
import com.myna.core.transcript.RawSentence
import com.myna.core.transcript.RawTranscript
import com.myna.core.transcript.TranscriptValidator
import com.myna.core.transcript.ValidationResult

/**
 * 채워진 현장 메모를 연습 계획으로 바꾼다.
 *
 * 영상이 없으므로 세션은 원본 재생 없이 돈다
 * ([com.myna.core.session.SessionOptions.sourceAudioAvailable]가 false).
 * 그 점만 빼면 다른 계획과 똑같이 취급된다 — 같은 반복 세션, 같은 카운트, 같은 복습 큐.
 */
public object NotePractice {

    /**
     * 단어 하나에 잡는 시간.
     *
     * 원본이 없으니 실제 발화 길이를 알 수 없다. 일일 예산에 얼마를 더할지 정하려면
     * 어림값이 필요하다. §8이 목표로 삼는 A1~A2 구간의 편안한 속도가 분당 120단어 안쪽이므로
     * 단어당 0.5초로 잡는다.
     */
    public const val MS_PER_WORD: Int = 500

    /** 한 단어짜리 문장도 최소한 이만큼은 말하게 된다. */
    public const val MIN_SPEECH_MS: Int = 1_500

    public fun estimateSpeechMs(text: String): Int {
        val words = text.split(WORD_SPLIT).count { it.isNotBlank() }
        return (words * MS_PER_WORD).coerceAtLeast(MIN_SPEECH_MS)
    }

    /**
     * 연습 계획을 만든다. 영어 문장이 비어 있으면 null.
     *
     * 손으로 적은 문장도 [TranscriptValidator]를 그대로 통과시킨다. 검증을 건너뛰면
     * 다른 경로에서는 불가능한 상태(키워드 0개 등)가 이 경로로만 흘러 들어온다.
     */
    public fun toPlan(note: FieldNote, dailyTargetSec: Int): VideoPlan? {
        val english = note.englishText?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val durationMs = estimateSpeechMs(english)

        val raw = RawTranscript(
            schemaVersion = 1,
            source = VideoSource.NOTE.name,
            sourceRef = note.id,
            language = "en",
            type = null, // V-7이 DIALOGUE로 폴백한다
            cefr = null, // V-7이 B1으로 폴백한다
            timestampUnit = TimestampUnit.MILLISECOND.name,
            speechStartMs = 0,
            speechEndMs = durationMs,
            sentences = listOf(
                RawSentence(
                    index = 0,
                    text = english,
                    translationKo = note.koreanMemo,
                    startMs = 0,
                    endMs = durationMs,
                    keywords = emptyList(), // V-6이 내용어로 채운다
                ),
            ),
        )

        val accepted = TranscriptValidator.validate(raw) as? ValidationResult.Accepted ?: return null
        val suggested = RepsCalculator.suggestedReps(dailyTargetSec, accepted.transcript)
        return VideoPlan(
            id = VideoPlanId.of(VideoSource.NOTE, note.id),
            transcript = accepted.transcript,
            suggestedReps = suggested,
            targetReps = suggested,
        )
    }

    private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}']+")
}
