package com.myna.core.transcribe

import com.myna.core.model.VideoSource
import com.myna.core.transcript.TranscriptValidator
import com.myna.core.transcript.ValidationResult
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeminiExchangeTest {

    private val videoId = "X_P7je26g5w"

    // --- 요청 ---

    @Test
    fun `영상을 내려받지 않고 URL만 넘긴다`() {
        // 앱 내 다운로드는 ToS 위반이다(§2.2). 모델이 직접 가져간다.
        val parts = GeminiExchange.requestForYouTube(videoId)["contents"]!!
            .jsonArray.first().jsonObject["parts"]!!.jsonArray

        val fileUri = parts.first().jsonObject["file_data"]!!.jsonObject["file_uri"]!!.jsonPrimitive.content
        assertEquals("https://www.youtube.com/watch?v=$videoId", fileUri)
    }

    @Test
    fun `구조화 출력을 강제한다`() {
        // 프롬프트로만 "JSON으로 답해"라고 하면 코드펜스가 섞여 파싱이 깨진다 (§1.3).
        val config = GeminiExchange.requestForYouTube(videoId)["generationConfig"]!!.jsonObject
        assertEquals("application/json", config["responseMimeType"]!!.jsonPrimitive.content)
        assertNotNull(config["responseSchema"])
    }

    @Test
    fun `스키마에 산술 결과가 없다`() {
        // §1.2 — speechSec·wpm·suggestedReps는 앱이 타임스탬프로 계산한다.
        val properties = TranscriptionSchema.responseSchema["properties"]!!.jsonObject.keys
        assertTrue("speechSec" !in properties)
        assertTrue("wpm" !in properties)
        assertTrue("suggestedReps" !in properties)
        // 판단 영역은 들어 있다
        assertTrue("cefr" in properties)
        assertTrue("type" in properties)
    }

    @Test
    fun `재요청은 temperature를 0으로 낮춘다`() {
        // §4.3 — 파싱 실패 시 같은 입력으로 1회 재요청한다.
        val retry = GeminiExchange.retryRequestForYouTube(videoId)["generationConfig"]!!.jsonObject
        assertEquals("0.0", retry["temperature"]!!.jsonPrimitive.content)
        // 나머지는 그대로여야 같은 입력이다
        assertEquals("application/json", retry["responseMimeType"]!!.jsonPrimitive.content)
    }

    // --- 응답 ---

    private fun envelope(inner: String) = """
        {"candidates":[{"content":{"parts":[{"text":${'"'}${inner.replace("\"", "\\\"")}${'"'}}]}}]}
    """.trimIndent()

    private val transcriptJson = """
        {"language":"en","type":"DIALOGUE","cefr":"A2","speechStartMs":0,"speechEndMs":4000,
         "sentences":[{"index":0,"text":"What are you up to this weekend?",
         "translationKo":"이번 주말에 뭐 해?","transliterationKo":"왓 아 유 업 투 디스 위켄드",
         "startMs":0,"endMs":2000,"keywords":["up to","weekend"]}]}
    """.trimIndent().replace("\n", " ")

    @Test
    fun `응답 봉투 안의 JSON을 꺼낸다`() {
        // 구조화 출력이라도 결과는 parts[0].text 안에 문자열로 온다. 한 겹 더 파싱해야 한다.
        val raw = GeminiExchange.parseTranscript(envelope(transcriptJson), videoId)
        assertNotNull(raw)
        assertEquals(1, raw.sentences.size)
        assertEquals("What are you up to this weekend?", raw.sentences.single().text)
    }

    @Test
    fun `source와 sourceRef는 우리가 채운다`() {
        // 모델이 알 수 없는 값이다. 스키마에서 빼 두고 여기서 넣는다.
        val raw = GeminiExchange.parseTranscript(envelope(transcriptJson), videoId)!!
        assertEquals(VideoSource.YOUTUBE.name, raw.source)
        assertEquals(videoId, raw.sourceRef)
        assertEquals(1, raw.schemaVersion)
    }

    @Test
    fun `코드펜스로 감싸 와도 읽는다`() {
        val fenced = "```json\\n$transcriptJson\\n```"
        val raw = GeminiExchange.parseTranscript(envelope(fenced), videoId)
        assertNotNull(raw)
    }

    @Test
    fun `전사 결과가 검증을 그대로 통과한다`() {
        // 이 연결이 이번 작업의 핵심이다 — 모델 응답이 기존 검증 규칙에 그대로 들어가야 한다.
        val raw = GeminiExchange.parseTranscript(envelope(transcriptJson), videoId)!!
        val result = TranscriptValidator.validate(raw)
        val accepted = assertIs<ValidationResult.Accepted>(result)
        assertEquals(1, accepted.transcript.sentences.size)
        assertEquals(listOf("up to", "weekend"), accepted.transcript.sentences.single().keywords)
    }

    @Test
    fun `깨진 응답에 예외를 던지지 않는다`() {
        assertNull(GeminiExchange.parseTranscript("not json at all", videoId))
        assertNull(GeminiExchange.parseTranscript("""{"candidates":[]}""", videoId))
        assertNull(GeminiExchange.parseTranscript(envelope("{ broken"), videoId))
    }

    @Test
    fun `오류 응답에서 메시지를 꺼낸다`() {
        val body = """{"error":{"code":400,"message":"API key not valid"}}"""
        assertEquals("API key not valid", GeminiExchange.errorMessage(body))
        assertNull(GeminiExchange.errorMessage(envelope(transcriptJson)))
    }
}
