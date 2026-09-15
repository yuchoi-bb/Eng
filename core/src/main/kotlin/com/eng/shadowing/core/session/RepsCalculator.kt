package com.eng.shadowing.core.session

import com.eng.shadowing.core.model.Transcript
import com.eng.shadowing.core.model.VideoType
import kotlin.math.ceil

/**
 * 반복 횟수 산출. REQUIREMENTS §4.2 / §4.4.
 *
 * 이 계산을 LLM에 맡기지 않는 것이 §9.4의 결정이다. 그래서 여기 있고, 그래서 테스트가 있다.
 */
public object RepsCalculator {

    /** §4.2 — 자동 제시 횟수의 하한/상한. */
    public const val MIN_REPS: Int = 5
    public const val MAX_REPS: Int = 50

    /**
     * 영상의 순 발화 시간(초). TRANSCRIPTION_SCHEMA §6에서
     * `speechSec = speechEndMs - speechStartMs`로 확정됐다.
     *
     * 전사 결과의 부산물이므로 추가 API 호출이 없다.
     */
    public fun speechSec(transcript: Transcript): Int {
        val ms = transcript.speechEndMs - transcript.speechStartMs
        return if (ms <= 0) 0 else (ms + 999) / 1000 // 올림 — 0.4초짜리가 0초가 되면 나눗셈이 터진다
    }

    /**
     * 분당 단어 수. TRANSCRIPTION_SCHEMA §1.2 — LLM에 물으면 매번 다른 값이 나오므로
     * 단어 수 ÷ 발화 시간으로 계산한다.
     */
    public fun wpm(transcript: Transcript): Int {
        val seconds = speechSec(transcript)
        if (seconds <= 0) return 0
        val words = transcript.sentences.sumOf { sentence ->
            sentence.text.split(WORD_SPLIT).count { it.isNotBlank() }
        }
        return (words * 60.0 / seconds).toInt()
    }

    /**
     * 자동 모드의 제시 횟수. §4.2의 계산식에 §4.4의 유형별 보정을 얹는다.
     *
     * **보정과 클램프의 순서**: 명세는 계산식(§4.2)과 `DRILL → reps × 0.5`(§4.4)를
     * 따로 적어 두어 순서가 모호하다. 여기서는 **보정을 먼저 하고 클램프를 마지막에** 건다.
     * 40초 DRILL 영상을 예로 들면 `ceil(300/40) = 8 → ×0.5 = 4`인데, 클램프를 먼저 걸면
     * 8이 그대로 남아 DRILL 보정이 무의미해지고, 나중에 걸면 하한 5가 지켜진다.
     * 하한 5는 "최소한 이만큼은 말해야 한다"는 바닥이므로 마지막에 적용하는 쪽이 의도에 맞다.
     */
    public fun suggestedReps(dailyTargetSec: Int, transcript: Transcript): Int {
        val seconds = speechSec(transcript)
        if (seconds <= 0) return MIN_REPS
        val raw = ceil(dailyTargetSec.toDouble() / seconds).toInt()
        val adjusted = (raw * multiplierFor(transcript.type)).toInt()
        return adjusted.coerceIn(MIN_REPS, MAX_REPS)
    }

    /** §4.4 — DRILL은 원본이 이미 반복 구조라 절반만 돌린다. */
    private fun multiplierFor(type: VideoType): Double = when (type) {
        VideoType.DRILL -> 0.5
        VideoType.SINGLE, VideoType.DIALOGUE -> 1.0
    }

    private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}']+")
}
