package com.myna.core.transcribe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Files API에 올린 파일. 영상은 올린 뒤 처리가 끝나야([State.ACTIVE]) 전사에 쓸 수 있다. */
public data class GeminiFile(
    /** `files/abc123` — 상태 조회와 삭제에 쓴다. */
    val name: String,
    /** 전사 요청의 `file_data.file_uri`에 넣는다. */
    val uri: String,
    val mimeType: String?,
    val state: State,
) {
    public enum class State { PROCESSING, ACTIVE, FAILED, UNKNOWN }
}

/**
 * Gemini Files API — 기기 영상을 전사하려면 먼저 올려야 한다.
 *
 * 요청 본문에 영상을 직접 담는 방식(inline_data)은 20MB까지라 쇼츠도 넘기 쉽다. Files API는
 * 2GB까지 받고, 올린 파일은 48시간 뒤 스스로 지워진다. 올리는 방법은 재개 가능 업로드다:
 * 시작 요청으로 업로드 URL을 받고, 그 URL에 바이트를 보내며 마무리한다.
 */
public object GeminiFiles {

    public const val UPLOAD_URL: String = "https://generativelanguage.googleapis.com/upload/v1beta/files"

    /**
     * 휴대폰에서 올릴 만한 상한. API 한도(2GB)보다 훨씬 낮게 둔다 — 긴 영상은 전사도
     * 쉐도잉도 맞지 않고, 모바일 회선으로 수백 MB를 올리다 끊기면 처음부터다.
     */
    public const val MAX_UPLOAD_BYTES: Long = 300L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 업로드 시작 요청 본문. */
    public fun startBody(displayName: String): JsonObject = buildJsonObject {
        putJsonObject("file") { put("display_name", displayName) }
    }

    /**
     * 업로드 완료 응답(`{"file": {...}}`)이나 상태 조회 응답(파일 객체 그대로)을 읽는다.
     */
    public fun parseFile(body: String): GeminiFile? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val file = root["file"]?.jsonObject ?: root
        val name = file["name"]?.jsonPrimitive?.content ?: return null
        val uri = file["uri"]?.jsonPrimitive?.content ?: return null
        GeminiFile(
            name = name,
            uri = uri,
            mimeType = file["mimeType"]?.jsonPrimitive?.content,
            state = when (file["state"]?.jsonPrimitive?.content) {
                "PROCESSING" -> GeminiFile.State.PROCESSING
                "ACTIVE" -> GeminiFile.State.ACTIVE
                "FAILED" -> GeminiFile.State.FAILED
                // 상태가 비어 오는 경우가 있다. 처리 중으로 보고 조회를 이어 간다.
                null -> GeminiFile.State.PROCESSING
                else -> GeminiFile.State.UNKNOWN
            },
        )
    }.getOrNull()
}
