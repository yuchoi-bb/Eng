package com.myna.core.playback

import com.myna.core.model.WordTiming
import com.myna.core.sentence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 소리에 맞춰 글자를 칠하는 시간표. */
class WordTimelineTest {

    private val text = "I love running"

    @Test
    fun `단어 시각이 없으면 글자 수에 비례해 나눈다`() {
        val timeline = WordTimeline.build(sentence(0, text = text, startMs = 0, endMs = 1400))
        assertEquals(3, timeline.size)
        assertEquals(0, timeline[0].startMs)
        assertEquals(1400, timeline.last().endMs)
        // 반쯤 왔으면 대략 절반의 글자가 칠해져 있다.
        assertEquals(7f, WordTimeline.filledChars(timeline, 700f), 0.01f)
    }

    @Test
    fun `단어 시각이 있으면 그 시각을 따른다`() {
        val s = sentence(0, text = text, startMs = 0, endMs = 3000).copy(
            words = listOf(
                WordTiming("I", 0, 200),
                WordTiming("love", 1000, 1400),
                WordTiming("running", 2000, 3000),
            ),
        )
        val timeline = WordTimeline.build(s)
        // "I" 다음 말 없는 구간에는 "I"까지만 칠해져 있다.
        assertEquals(1f, WordTimeline.filledChars(timeline, 600f))
        // "love"의 중간
        assertEquals(4f, WordTimeline.filledChars(timeline, 1200f), 0.01f)
        assertEquals(text.length.toFloat(), WordTimeline.filledChars(timeline, 5000f))
    }

    @Test
    fun `문장부호와 대소문자는 무시하고 짝짓는다`() {
        val s = sentence(0, text = "Oh, my gosh!", startMs = 0, endMs = 1500).copy(
            words = listOf(WordTiming("oh", 0, 300), WordTiming("My", 400, 600), WordTiming("gosh", 700, 1200)),
        )
        val timeline = WordTimeline.build(s)
        assertEquals(listOf(0, 400, 700), timeline.map { it.startMs })
    }

    @Test
    fun `짝이 안 맞는 토큰은 앞뒤 사이를 나눈다`() {
        // 모델이 "11"을 "eleven"으로 적었다.
        val s = sentence(0, text = "11 years ago", startMs = 0, endMs = 2000).copy(
            words = listOf(WordTiming("eleven", 0, 500), WordTiming("years", 600, 1000), WordTiming("ago", 1100, 1500)),
        )
        val timeline = WordTimeline.build(s)
        assertEquals(0, timeline[0].startMs)
        assertEquals(600, timeline[0].endMs)
        assertEquals(600, timeline[1].startMs)
    }

    @Test
    fun `하나도 짝이 안 맞으면 글자 수 비례로 돌아간다`() {
        val s = sentence(0, text = text, startMs = 0, endMs = 1400).copy(
            words = listOf(WordTiming("completely", 0, 500), WordTiming("different", 500, 1000)),
        )
        assertEquals(WordTimeline.build(sentence(0, text = text, startMs = 0, endMs = 1400)), WordTimeline.build(s))
    }

    @Test
    fun `겹치거나 순서가 뒤집힌 시각을 바로잡는다`() {
        val fixed = WordTimeline.sanitize(
            listOf(WordTiming("b", 500, 900), WordTiming("a", 0, 700), WordTiming(" ", 1, 2), WordTiming("c", 1000, 800)),
        )
        assertEquals(listOf("a", "b", "c"), fixed.map { it.text })
        assertEquals(500, fixed[0].endMs) // 다음 단어 시작에서 끊는다
        assertTrue(fixed.all { it.endMs >= it.startMs })
    }

    @Test
    fun `칠하는 구간은 단어 시각이 문장 경계를 넘어도 담는다`() {
        val s = sentence(0, text = text, startMs = 1000, endMs = 2000).copy(
            words = listOf(WordTiming("I", 900, 1000), WordTiming("running", 1800, 2300)),
        )
        assertEquals(900 to 2300, WordTimeline.span(s))
    }
}
