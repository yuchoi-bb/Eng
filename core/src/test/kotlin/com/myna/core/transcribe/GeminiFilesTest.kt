package com.myna.core.transcribe

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 기기 영상을 전사하려면 먼저 Files API로 올린다. */
class GeminiFilesTest {

    @Test
    fun `업로드 완료 응답을 읽는다`() {
        val body = """
            {"file":{"name":"files/abc123","mimeType":"video/mp4","sizeBytes":"1234",
             "uri":"https://generativelanguage.googleapis.com/v1beta/files/abc123","state":"PROCESSING"}}
        """.trimIndent()
        val file = GeminiFiles.parseFile(body)!!
        assertEquals("files/abc123", file.name)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/files/abc123", file.uri)
        assertEquals("video/mp4", file.mimeType)
        assertEquals(GeminiFile.State.PROCESSING, file.state)
    }

    @Test
    fun `상태 조회 응답은 파일 객체가 그대로 온다`() {
        val body = """{"name":"files/abc123","uri":"https://x/files/abc123","state":"ACTIVE"}"""
        assertEquals(GeminiFile.State.ACTIVE, GeminiFiles.parseFile(body)!!.state)
    }

    @Test
    fun `처리 실패를 알아본다`() {
        val body = """{"name":"files/a","uri":"https://x/files/a","state":"FAILED"}"""
        assertEquals(GeminiFile.State.FAILED, GeminiFiles.parseFile(body)!!.state)
    }

    @Test
    fun `상태가 비어 있으면 처리 중으로 본다`() {
        // 조회를 이어 가야 한다. 모른다고 멈추면 멀쩡한 파일을 버린다.
        val body = """{"name":"files/a","uri":"https://x/files/a"}"""
        assertEquals(GeminiFile.State.PROCESSING, GeminiFiles.parseFile(body)!!.state)
    }

    @Test
    fun `URI가 없거나 깨진 응답은 null`() {
        assertNull(GeminiFiles.parseFile("""{"file":{"name":"files/a"}}"""))
        assertNull(GeminiFiles.parseFile("""{"error":{"message":"bad"}}"""))
        assertNull(GeminiFiles.parseFile("not json"))
    }

    @Test
    fun `시작 요청에 표시 이름을 담는다`() {
        val body = GeminiFiles.startBody("myna-upload")
        assertEquals("myna-upload", body["file"]!!.jsonObject["display_name"]!!.jsonPrimitive.content)
    }
}
