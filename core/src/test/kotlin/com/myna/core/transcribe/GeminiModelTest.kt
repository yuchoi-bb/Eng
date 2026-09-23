package com.myna.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 모델 이름을 코드에 박지 않는 대신, 목록에서 고르는 규칙을 고정한다.
 * 이름이 바뀌어도 앱이 따라가야 한다.
 */
class GeminiModelTest {

    private fun models(vararg entries: Pair<String, List<String>>) = """
        {"models":[${entries.joinToString(",") { (name, methods) ->
            """{"name":"models/$name","supportedGenerationMethods":[${methods.joinToString(",") { "\"$it\"" }}]}"""
        }}]}
    """.trimIndent()

    @Test
    fun `generateContent를 지원하지 않는 모델은 거른다`() {
        val body = models(
            "text-embedding-004" to listOf("embedContent"),
            "gemini-3.5-flash" to listOf("generateContent"),
        )
        assertEquals("gemini-3.5-flash", GeminiModel.pickForVideo(body))
    }

    @Test
    fun `flash 계열을 고른다`() {
        // 영상 한 편 전사에 pro급은 과하고 느리다.
        val body = models(
            "gemini-3.5-pro" to listOf("generateContent"),
            "gemini-3.5-flash" to listOf("generateContent"),
        )
        assertEquals("gemini-3.5-flash", GeminiModel.pickForVideo(body))
    }

    @Test
    fun `미리보기와 실험 딱지는 피한다`() {
        // 예고 없이 사라지는 이름들이다.
        val body = models(
            "gemini-3.9-flash-preview-11-20" to listOf("generateContent"),
            "gemini-3.5-flash" to listOf("generateContent"),
        )
        assertEquals("gemini-3.5-flash", GeminiModel.pickForVideo(body))
    }

    @Test
    fun `안정판이 하나도 없으면 미리보기라도 쓴다`() {
        val body = models("gemini-4.0-flash-preview" to listOf("generateContent"))
        assertEquals("gemini-4.0-flash-preview", GeminiModel.pickForVideo(body))
    }

    @Test
    fun `버전이 올라가면 새 이름을 고른다`() {
        val body = models(
            "gemini-3.5-flash" to listOf("generateContent"),
            "gemini-3.8-flash" to listOf("generateContent"),
        )
        assertEquals("gemini-3.8-flash", GeminiModel.pickForVideo(body))
    }

    @Test
    fun `쓸 만한 모델이 없으면 null이다`() {
        assertNull(GeminiModel.pickForVideo(models("gemini-3.5-pro" to listOf("embedContent"))))
        assertNull(GeminiModel.pickForVideo("그냥 문자열"))
        assertNull(GeminiModel.pickForVideo("""{"models":[]}"""))
    }
}
