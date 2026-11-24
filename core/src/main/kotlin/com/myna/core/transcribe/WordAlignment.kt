package com.myna.core.transcribe

import com.myna.core.model.Sentence
import com.myna.core.model.WordTiming
import com.myna.core.playback.WordTimeline
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 이미 있는 문장에 단어별 시각을 붙이는 요청 (TRANSCRIPTION_SCHEMA §4.6).
 *
 * 다시 받아 적게 하지 않는다. 사용자가 지우고 고친 문장을 그대로 보내고, 모델은 영상을 들으며
 * 각 단어가 언제 말해지는지만 답한다. 그래서 저장된 영상에도 편집을 잃지 않고 적용된다.
 */
public object WordAlignment {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    public val responseSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sentences") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("index") { put("type", "integer") }
                        putJsonObject("words") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("text") { put("type", "string") }
                                    putJsonObject("startMs") { put("type", "integer") }
                                    putJsonObject("endMs") { put("type", "integer") }
                                }
                                putJsonArray("required") {
                                    add(kotlinx.serialization.json.JsonPrimitive("text"))
                                    add(kotlinx.serialization.json.JsonPrimitive("startMs"))
                                    add(kotlinx.serialization.json.JsonPrimitive("endMs"))
                                }
                            }
                        }
                    }
                    putJsonArray("required") {
                        add(kotlinx.serialization.json.JsonPrimitive("index"))
                        add(kotlinx.serialization.json.JsonPrimitive("words"))
                    }
                }
            }
        }
        putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("sentences")) }
    }

    public fun prompt(sentences: List<Sentence>): String = buildString {
        appendLine("You are aligning an existing English transcript to this video, word by word.")
        appendLine("Do NOT re-transcribe or change the text. For each sentence below, return one entry")
        appendLine("per space-separated token of its text, in order, with the time that token is spoken.")
        appendLine("- startMs/endMs: milliseconds from the start of the video, when the word starts and ends.")
        appendLine("- The given sentence times are approximate; the words should fall near them.")
        appendLine("- If a token is not clearly audible, estimate from its neighbours rather than skipping it.")
        appendLine()
        appendLine("Sentences (index | approx start-end seconds | text):")
        sentences.forEach { sentence ->
            appendLine("${sentence.index} | ${seconds(sentence.startMs)}-${seconds(sentence.endMs)} | ${sentence.text}")
        }
    }

    public fun requestForYouTube(videoId: String, sentences: List<Sentence>, retry: Boolean = false): JsonObject =
        request(
            buildJsonObject {
                putJsonObject("file_data") { put("file_uri", "https://www.youtube.com/watch?v=$videoId") }
            },
            sentences,
            retry,
        )

    public fun requestForUploadedVideo(
        fileUri: String,
        mimeType: String,
        sentences: List<Sentence>,
        retry: Boolean = false,
    ): JsonObject = request(
        buildJsonObject {
            putJsonObject("file_data") {
                put("mime_type", mimeType)
                put("file_uri", fileUri)
            }
        },
        sentences,
        retry,
    )

    private fun request(filePart: JsonObject, sentences: List<Sentence>, retry: Boolean): JsonObject = buildJsonObject {
        put(
            "contents",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put(
                            "parts",
                            buildJsonArray {
                                add(filePart)
                                add(buildJsonObject { put("text", prompt(sentences)) })
                            },
                        )
                    },
                )
            },
        )
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseSchema", responseSchema)
            put("temperature", if (retry) 0.0 else 0.1)
        }
    }

    /**
     * 응답을 문장 번호 → 단어 시각으로 읽는다. 읽을 수 없으면 null.
     * 요청에 없던 번호는 버린다.
     */
    public fun parse(responseBody: String, sentences: List<Sentence>): Map<Int, List<WordTiming>>? {
        val payload = GeminiExchange.extractJsonText(responseBody) ?: return null
        val raw = runCatching { json.decodeFromString(Response.serializer(), payload) }.getOrNull() ?: return null
        val known = sentences.map { it.index }.toSet()
        return raw.sentences
            .filter { it.index in known }
            .associate { entry ->
                entry.index to WordTimeline.sanitize(
                    entry.words.mapNotNull { word ->
                        val text = word.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        WordTiming(text.trim(), word.startMs ?: return@mapNotNull null, word.endMs ?: return@mapNotNull null)
                    },
                )
            }
            .filterValues { it.isNotEmpty() }
    }

    private fun seconds(ms: Int): String = "%.1f".format(java.util.Locale.US, ms / 1000.0)

    @Serializable
    private data class Response(val sentences: List<Entry> = emptyList())

    @Serializable
    private data class Entry(val index: Int = -1, val words: List<Word> = emptyList())

    @Serializable
    private data class Word(val text: String? = null, val startMs: Int? = null, val endMs: Int? = null)
}
