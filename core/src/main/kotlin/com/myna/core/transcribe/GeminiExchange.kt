package com.myna.core.transcribe

import com.myna.core.model.VideoSource
import com.myna.core.transcript.RawTranscript
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Gemini 요청 본문과 응답 해석.
 *
 * HTTP는 앱이 맡고, 무엇을 보내고 무엇을 읽을지는 여기서 정한다. 그래야 기기 없이
 * 검증된다 — 이 계층이 틀리면 원인이 네트워크인지 형식인지 구분할 수 없게 된다.
 */
public object GeminiExchange {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 유튜브 영상 전사 요청.
     *
     * 영상을 내려받지 않고 URL만 넘긴다 — 앱 내 다운로드는 ToS 위반이고(§2.2),
     * 모델이 직접 가져가므로 기기 대역폭도 들지 않는다.
     */
    public fun requestForYouTube(videoId: String): JsonObject = buildJsonObject {
        put(
            "contents",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put(
                            "parts",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        putJsonObject("file_data") {
                                            put("file_uri", "https://www.youtube.com/watch?v=$videoId")
                                        }
                                    },
                                )
                                add(buildJsonObject { put("text", TranscriptionPrompt.forVideo) })
                            },
                        )
                    },
                )
            },
        )
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseSchema", TranscriptionSchema.responseSchema)
            // 전사는 창작이 아니다. 낮게 둘수록 같은 영상에서 같은 결과가 나온다.
            put("temperature", 0.2)
        }
    }

    /** 재요청용. TRANSCRIPTION_SCHEMA §4.3 — 파싱 실패 시 temperature를 낮춰 1회만 다시 묻는다. */
    public fun retryRequestForYouTube(videoId: String): JsonObject {
        val base = requestForYouTube(videoId)
        return buildJsonObject {
            base.forEach { (key, value) ->
                if (key == "generationConfig") {
                    putJsonObject("generationConfig") {
                        value.jsonObject.forEach { (k, v) -> if (k != "temperature") put(k, v) }
                        put("temperature", 0.0)
                    }
                } else {
                    put(key, value)
                }
            }
        }
    }

    /**
     * 응답에서 전사 결과를 꺼낸다.
     *
     * 구조화 출력이라도 모델은 여전히 `candidates[0].content.parts[0].text` 안에 **문자열로**
     * JSON을 담아 보낸다. 한 겹 더 파싱해야 한다.
     */
    public fun parseTranscript(responseBody: String, videoId: String): RawTranscript? {
        val payload = extractJsonText(responseBody) ?: return null
        val raw = runCatching { json.decodeFromString(RawTranscript.serializer(), payload) }.getOrNull()
            ?: return null
        // source와 sourceRef는 모델이 아니라 우리가 안다. 스키마에서 빼 두고 여기서 채운다.
        return raw.copy(
            schemaVersion = 1,
            source = VideoSource.YOUTUBE.name,
            sourceRef = videoId,
        )
    }

    /** 응답이 실패일 때 사용자에게 보여 줄 만한 한 줄. */
    public fun errorMessage(responseBody: String): String? = runCatching {
        json.parseToJsonElement(responseBody).jsonObject["error"]?.jsonObject
            ?.get("message")?.jsonPrimitive?.content
    }.getOrNull()

    private fun extractJsonText(responseBody: String): String? = runCatching {
        json.parseToJsonElement(responseBody).jsonObject["candidates"]
            ?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
            ?.firstNotNullOfOrNull { it.jsonObject["text"]?.jsonPrimitive?.content }
            ?.let(::stripCodeFence)
    }.getOrNull()

    /**
     * 코드펜스를 벗긴다.
     *
     * responseSchema를 쓰면 대개 순수 JSON이 오지만, 모델이 ```json 으로 감싸 보내는 경우가
     * 남아 있다. 한 줄로 막을 수 있는 실패를 굳이 재요청으로 넘기지 않는다.
     */
    private fun stripCodeFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }
}
