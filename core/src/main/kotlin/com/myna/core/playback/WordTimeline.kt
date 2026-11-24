package com.myna.core.playback

import com.myna.core.model.Sentence
import com.myna.core.model.WordTiming

/** 문장 속 한 토큰(띄어쓰기로 나눈 덩어리)의 글자 범위와 발화 구간. */
public data class TokenTiming(
    /** 문장 text에서 이 토큰이 차지하는 글자 범위 [charStart, charEnd). */
    val charStart: Int,
    val charEnd: Int,
    val startMs: Int,
    val endMs: Int,
)

/**
 * 소리에 맞춰 글자를 칠하기 위한 시간표.
 *
 * 단어별 시각([Sentence.words])이 있으면 문장의 토큰과 순서대로 짝지어 쓴다. 모델이 숫자를
 * 풀어 쓰거나("11" ↔ "eleven") 토큰을 다르게 나눠 짝이 안 맞는 토큰은 앞뒤 사이에서 글자 수로
 * 나눈다. 단어별 시각이 아예 없으면 문장 시작~끝을 글자 수에 비례해 나눈다.
 */
public object WordTimeline {

    /** 받은 단어 시각을 쓸 수 있게 다듬는다. 빈 글자를 빼고, 순서와 구간을 바로잡는다. */
    public fun sanitize(words: List<WordTiming>): List<WordTiming> {
        val cleaned = words
            .filter { it.text.isNotBlank() && it.startMs >= 0 && it.endMs >= 0 }
            .map { if (it.endMs < it.startMs) it.copy(endMs = it.startMs) else it }
            .sortedBy { it.startMs }
        // 겹치면 앞 단어를 뒤 단어 시작에서 끊는다. 칠하는 위치가 뒤로 가면 안 된다.
        return cleaned.mapIndexed { i, word ->
            val next = cleaned.getOrNull(i + 1)
            if (next != null && word.endMs > next.startMs) word.copy(endMs = maxOf(word.startMs, next.startMs)) else word
        }
    }

    /** 띄어쓰기로 나눈 토큰의 글자 범위. */
    public fun tokenRanges(text: String): List<IntRange> =
        Regex("\\S+").findAll(text).map { it.range }.toList()

    /** 글자를 칠할 수 있는 시간 범위. 단어 시각이 문장 경계를 조금 넘을 수 있어 둘을 합친다. */
    public fun span(sentence: Sentence): Pair<Int, Int> {
        val first = sentence.words.firstOrNull()?.startMs ?: sentence.startMs
        val last = sentence.words.lastOrNull()?.endMs ?: sentence.endMs
        return minOf(first, sentence.startMs) to maxOf(last, sentence.endMs)
    }

    public fun build(sentence: Sentence): List<TokenTiming> {
        val ranges = tokenRanges(sentence.text)
        if (ranges.isEmpty()) return emptyList()

        val matched = arrayOfNulls<Pair<Int, Int>>(ranges.size)
        var cursor = 0
        if (sentence.words.isNotEmpty()) {
            ranges.forEachIndexed { i, range ->
                val token = normalize(sentence.text.substring(range.first, range.last + 1))
                if (token.isEmpty()) return@forEachIndexed
                // 모델이 단어를 하나 빼먹거나 더 넣은 경우를 넘어가도록 몇 개 앞까지 본다.
                val window = sentence.words.subList(cursor, minOf(cursor + LOOKAHEAD, sentence.words.size))
                val hit = window.indexOfFirst { sameWord(normalize(it.text), token) }
                if (hit >= 0) {
                    val word = sentence.words[cursor + hit]
                    matched[i] = word.startMs to word.endMs
                    cursor += hit + 1
                }
            }
        }
        if (matched.all { it == null }) return proportional(sentence, ranges)

        // 짝을 못 찾은 토큰은 앞뒤로 짝지어진 시각 사이를 글자 수로 나눈다.
        val (spanStart, spanEnd) = span(sentence)
        val result = ArrayList<TokenTiming>(ranges.size)
        var i = 0
        while (i < ranges.size) {
            val hit = matched[i]
            if (hit != null) {
                result += TokenTiming(ranges[i].first, ranges[i].last + 1, hit.first, hit.second)
                i += 1
                continue
            }
            var j = i
            while (j < ranges.size && matched[j] == null) j += 1
            val from = result.lastOrNull()?.endMs ?: spanStart
            val to = matched.getOrNull(j)?.first ?: spanEnd
            val chars = (i until j).sumOf { ranges[it].last + 1 - ranges[it].first }.coerceAtLeast(1)
            var done = 0
            for (k in i until j) {
                val len = ranges[k].last + 1 - ranges[k].first
                val s = from + (to - from).coerceAtLeast(0) * done / chars
                done += len
                val e = from + (to - from).coerceAtLeast(0) * done / chars
                result += TokenTiming(ranges[k].first, ranges[k].last + 1, s, e)
            }
            i = j
        }
        return result
    }

    /**
     * [positionMs]까지 말한 글자 수. 소수점은 지금 말하는 글자의 진행 정도다 — 칠하는 경계를
     * 부드럽게 번지게 하는 데 쓴다.
     */
    public fun filledChars(timeline: List<TokenTiming>, positionMs: Float): Float {
        var filled = 0f
        for (token in timeline) {
            when {
                positionMs >= token.endMs -> filled = token.charEnd.toFloat()
                positionMs >= token.startMs -> {
                    val length = (token.endMs - token.startMs).coerceAtLeast(1)
                    val progress = (positionMs - token.startMs) / length
                    return token.charStart + (token.charEnd - token.charStart) * progress
                }
                else -> return filled
            }
        }
        return filled
    }

    private fun proportional(sentence: Sentence, ranges: List<IntRange>): List<TokenTiming> {
        val length = sentence.text.length.coerceAtLeast(1)
        val duration = (sentence.endMs - sentence.startMs).coerceAtLeast(0)
        return ranges.map { range ->
            TokenTiming(
                charStart = range.first,
                charEnd = range.last + 1,
                startMs = sentence.startMs + duration * range.first / length,
                endMs = sentence.startMs + duration * (range.last + 1) / length,
            )
        }
    }

    private fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() || it == '\'' }.replace("’", "'")

    private fun sameWord(a: String, b: String): Boolean =
        a.isNotEmpty() && b.isNotEmpty() && (a == b || a.startsWith(b) || b.startsWith(a))

    private const val LOOKAHEAD = 4
}
