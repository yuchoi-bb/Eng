package com.myna.core.transcribe

import com.myna.core.sentence
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 이미 있는 문장에 단어별 시각만 붙인다. */
class WordAlignmentTest {

    private val sentences = listOf(
        sentence(0, text = "I love running", startMs = 0, endMs = 1500),
        sentence(1, text = "Oh my gosh", startMs = 2000, endMs = 3000),
    )

    private fun envelope(inner: String): String {
        val escaped = inner.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"candidates":[{"content":{"parts":[{"text":"$escaped"}]}}]}"""
    }

    @Test
    fun `요청에 지금 문장을 그대로 담는다`() {
        val prompt = WordAlignment.prompt(sentences)
        assertTrue(prompt.contains("0 | 0.0-1.5 | I love running"))
        assertTrue(prompt.contains("Do NOT re-transcribe"))
    }

    @Test
    fun `유튜브는 URL을, 기기 영상은 올린 파일을 넘긴다`() {
        val yt = WordAlignment.requestForYouTube("abc", sentences)
        val ytPart = yt["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["file_data"]!!.jsonObject
        assertEquals("https://www.youtube.com/watch?v=abc", ytPart["file_uri"]!!.jsonPrimitive.content)

        val up = WordAlignment.requestForUploadedVideo("uri", "video/mp4", sentences, retry = true)
        assertEquals("0.0", up["generationConfig"]!!.jsonObject["temperature"]!!.jsonPrimitive.content)
    }

    @Test
    fun `응답을 문장 번호별 단어 시각으로 읽는다`() {
        val body = envelope(
            """{"sentences":[
              {"index":0,"words":[{"text":"I","startMs":0,"endMs":200},{"text":"love","startMs":300,"endMs":700}]},
              {"index":1,"words":[{"text":"Oh","startMs":2000,"endMs":2200}]},
              {"index":9,"words":[{"text":"x","startMs":1,"endMs":2}]}
            ]}""",
        )
        val parsed = WordAlignment.parse(body, sentences)!!
        assertEquals(setOf(0, 1), parsed.keys) // 요청에 없던 9번은 버린다
        assertEquals(listOf("I", "love"), parsed[0]!!.map { it.text })
    }

    @Test
    fun `깨진 응답은 null`() {
        assertNull(WordAlignment.parse("nope", sentences))
        assertNull(WordAlignment.parse(envelope("{ broken"), sentences))
    }
}
