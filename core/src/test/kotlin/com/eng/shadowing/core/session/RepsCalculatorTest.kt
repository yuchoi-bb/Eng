package com.eng.shadowing.core.session

import com.eng.shadowing.core.model.VideoType
import com.eng.shadowing.core.sentence
import com.eng.shadowing.core.transcript
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * REQUIREMENTS §4.2의 계산식과 그 아래 표에 적힌 값을 그대로 검증한다.
 * 이 수치가 바뀌면 명세가 바뀐 것이다.
 */
class RepsCalculatorTest {

    @Test
    fun `speechSec는 speechEndMs 빼기 speechStartMs다`() {
        // TRANSCRIPTION_SCHEMA §6에서 확정된 정의.
        assertEquals(40, RepsCalculator.speechSec(transcript(speechStartMs = 1_200, speechEndMs = 41_200)))
    }

    @Test
    fun `일반 쇼츠 40초는 8회를 제시한다`() {
        // §4.2 표 — ceil(300 / 40) = 8
        assertEquals(
            8,
            RepsCalculator.suggestedReps(300, transcript(speechStartMs = 0, speechEndMs = 40_000)),
        )
    }

    @Test
    fun `한 문장 영상 8초는 38회를 제시한다`() {
        // §4.2 표 — ceil(300 / 8) = 37.5 → 38
        assertEquals(
            38,
            RepsCalculator.suggestedReps(300, transcript(speechStartMs = 0, speechEndMs = 8_000)),
        )
    }

    @Test
    fun `제시 횟수는 5에서 50 사이로 묶인다`() {
        assertEquals(RepsCalculator.MIN_REPS, RepsCalculator.suggestedReps(300, transcript(speechEndMs = 300_000)))
        assertEquals(RepsCalculator.MAX_REPS, RepsCalculator.suggestedReps(300, transcript(speechEndMs = 1_000)))
    }

    @Test
    fun `DRILL은 절반만 반복한다`() {
        // §4.4 — 원본이 이미 반복 구조다. 40초면 8회의 절반 4회이지만 하한 5가 마지막에 걸린다.
        assertEquals(
            5,
            RepsCalculator.suggestedReps(300, transcript(type = VideoType.DRILL, speechEndMs = 40_000)),
        )
        // 하한에 닿지 않는 구간에서는 정확히 절반. ceil(300/8)=38 → 19
        assertEquals(
            19,
            RepsCalculator.suggestedReps(300, transcript(type = VideoType.DRILL, speechEndMs = 8_000)),
        )
    }

    @Test
    fun `발화 시간이 0이면 나눗셈 대신 하한을 돌려준다`() {
        assertEquals(RepsCalculator.MIN_REPS, RepsCalculator.suggestedReps(300, transcript(speechEndMs = 0)))
    }

    @Test
    fun `wpm은 단어 수 나누기 발화 시간이다`() {
        // 10단어 / 30초 = 20 wpm
        val t = transcript(
            speechStartMs = 0,
            speechEndMs = 30_000,
            sentences = listOf(sentence(0, text = "one two three four five six seven eight nine ten")),
        )
        assertEquals(20, RepsCalculator.wpm(t))
    }
}
