package com.myna.core

import com.myna.core.model.BreathGroup
import com.myna.core.model.Cefr
import com.myna.core.model.Sentence
import com.myna.core.model.TimestampUnit
import com.myna.core.model.Transcript
import com.myna.core.model.VideoPlan
import com.myna.core.model.VideoPlanId
import com.myna.core.model.VideoSource
import com.myna.core.model.VideoType

internal fun sentence(
    index: Int,
    text: String = "What are you up to this weekend?",
    startMs: Int = 0,
    endMs: Int = 2000,
    keywords: List<String> = listOf("up to", "weekend"),
    breathGroups: List<BreathGroup> = emptyList(),
): Sentence = Sentence(
    index = index,
    text = text,
    translationKo = "이번 주말에 뭐 해?",
    startMs = startMs,
    endMs = endMs,
    keywords = keywords,
    breathGroups = breathGroups,
)

internal fun transcript(
    type: VideoType = VideoType.DIALOGUE,
    speechStartMs: Int = 0,
    speechEndMs: Int = 40_000,
    timestampUnit: TimestampUnit = TimestampUnit.MILLISECOND,
    sentences: List<Sentence> = listOf(sentence(0)),
): Transcript = Transcript(
    schemaVersion = 1,
    source = VideoSource.UPLOAD,
    sourceRef = "fixture",
    language = "en",
    type = type,
    cefr = Cefr.A2,
    timestampUnit = timestampUnit,
    speechStartMs = speechStartMs,
    speechEndMs = speechEndMs,
    sentences = sentences,
)

internal fun plan(
    transcript: Transcript = transcript(),
    targetReps: Int = 1,
): VideoPlan = VideoPlan(
    id = VideoPlanId.of(transcript.source, transcript.sourceRef),
    transcript = transcript,
    suggestedReps = targetReps,
    targetReps = targetReps,
)

/** 지금 걸음의 단계를 모두 끝낸다. 걸음마다 단계 수가 다르므로 테스트는 이것으로 한 걸음씩 민다. */
internal fun com.myna.core.session.SessionEngine.completeStep(): com.myna.core.session.SessionEvent {
    while (true) {
        val event = completeStage()
        if (event !is com.myna.core.session.SessionEvent.StageAdvanced) return event
    }
}
