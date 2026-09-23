package com.myna.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 모델 이름을 코드에 박지 않는 대신 후보 순서를 고정한다.
 *
 * `models.list`는 어떤 모델이 영상을 받는지 알려 주지 않으므로, 이름으로 단정하지 않고
 * 순서만 정한 뒤 실제로 눌러 본다.
 */
class GeminiModelTest {

    private fun models(vararg entries: Pair<String, List<String>>) = """
        {"models":[${entries.joinToString(",") { (name, methods) ->
            """{"name":"models/$name","supportedGenerationMethods":[${methods.joinToString(",") { "\"$it\"" }}]}"""
        }}]}
    """.trimIndent()

    private fun gen(vararg names: String) =
        models(*names.map { it to listOf("generateContent") }.toTypedArray())

    @Test
    fun `generateContent를 지원하지 않는 모델은 뺀다`() {
        val body = models(
            "text-embedding-004" to listOf("embedContent"),
            "gemini-3.5-flash" to listOf("generateContent"),
        )
        assertEquals(listOf("gemini-3.5-flash"), GeminiModel.candidatesForVideo(body))
    }

    @Test
    fun `영상을 다룰 리 없는 모델은 아예 뺀다`() {
        // 호출해 볼 가치도 없다. 한 번의 왕복이 수십 초다.
        val candidates = GeminiModel.candidatesForVideo(
            gen("gemini-3.5-flash-tts", "imagen-4.0-generate", "gemini-live-2.5-flash", "gemini-3.5-flash"),
        )
        assertEquals(listOf("gemini-3.5-flash"), candidates)
    }

    @Test
    fun `flash 계열이 앞에 온다`() {
        val candidates = GeminiModel.candidatesForVideo(gen("gemini-3.5-pro", "gemini-3.5-flash"))
        assertEquals("gemini-3.5-flash", candidates.first())
        // pro도 후보로 남는다 — flash가 전부 거절당하면 써 봐야 한다.
        assertTrue("gemini-3.5-pro" in candidates)
    }

    @Test
    fun `omni처럼 특수 목적인 이름은 뒤로 민다`() {
        // 실제로 gemini-omni-1.1-flash가 "YouTube video input is not supported"로 거절했다.
        val candidates = GeminiModel.candidatesForVideo(gen("gemini-omni-1.1-flash", "gemini-3.5-flash"))
        assertEquals(listOf("gemini-3.5-flash", "gemini-omni-1.1-flash"), candidates)
    }

    @Test
    fun `미리보기는 안정판 뒤에 온다`() {
        val candidates = GeminiModel.candidatesForVideo(
            gen("gemini-3.9-flash-preview-11-20", "gemini-3.5-flash"),
        )
        assertEquals(listOf("gemini-3.5-flash", "gemini-3.9-flash-preview-11-20"), candidates)
    }

    @Test
    fun `같은 등급이면 최신 이름이 앞에 온다`() {
        val candidates = GeminiModel.candidatesForVideo(gen("gemini-3.5-flash", "gemini-3.8-flash"))
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.5-flash"), candidates)
    }

    @Test
    fun `쓸 만한 모델이 없으면 빈 목록이다`() {
        assertTrue(GeminiModel.candidatesForVideo(models("gemini-3.5-pro" to listOf("embedContent"))).isEmpty())
        assertTrue(GeminiModel.candidatesForVideo("그냥 문자열").isEmpty())
        assertTrue(GeminiModel.candidatesForVideo("""{"models":[]}""").isEmpty())
    }

    @Test
    fun `같은 이름이 두 번 나와도 한 번만 시도한다`() {
        val candidates = GeminiModel.candidatesForVideo(gen("gemini-3.5-flash", "gemini-3.5-flash"))
        assertEquals(1, candidates.size)
    }

    // --- 거절 사유 분류 ---

    @Test
    fun `영상을 못 받는다는 거절은 다음 후보로 넘어갈 신호다`() {
        assertEquals(
            GeminiErrorKind.MODEL_CAPABILITY,
            GeminiModel.classifyError("YouTube video input is not supported by the model."),
        )
        assertEquals(
            GeminiErrorKind.MODEL_CAPABILITY,
            GeminiModel.classifyError("models/foo is not found for API version v1beta"),
        )
    }

    @Test
    fun `키 문제는 모델을 바꿔도 같으므로 멈춘다`() {
        assertEquals(
            GeminiErrorKind.AUTH,
            GeminiModel.classifyError("API key not valid. Please pass a valid API key."),
        )
    }

    @Test
    fun `한도 초과도 멈춘다`() {
        assertEquals(GeminiErrorKind.QUOTA, GeminiModel.classifyError("Quota exceeded for quota metric"))
    }

    @Test
    fun `모르는 오류는 그대로 보여 준다`() {
        assertEquals(GeminiErrorKind.OTHER, GeminiModel.classifyError("The model is overloaded"))
        assertEquals(GeminiErrorKind.OTHER, GeminiModel.classifyError(null))
    }

    @Test
    fun `분류가 대소문자에 흔들리지 않는다`() {
        assertEquals(GeminiErrorKind.AUTH, GeminiModel.classifyError("API KEY NOT VALID"))
        assertFalse(GeminiModel.classifyError("Something else") == GeminiErrorKind.AUTH)
    }
}
