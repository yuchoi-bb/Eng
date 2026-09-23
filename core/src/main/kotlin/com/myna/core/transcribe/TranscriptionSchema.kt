package com.myna.core.transcribe

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Gemini 구조화 출력용 응답 스키마. TRANSCRIPTION_SCHEMA §1.3.
 *
 * 프롬프트로 "JSON으로 답해"라고만 하면 앞뒤에 설명 문구나 코드펜스가 섞여 파싱이 깨진다.
 * 스키마를 넘기면 모델이 그 형태로만 답한다.
 *
 * **산술 결과는 스키마에 없다** — §1.2에 따라 `speechSec`·`wpm`·`suggestedReps`는 앱이
 * 타임스탬프로 계산한다. 모델에게 물으면 매번 다른 값이 나온다.
 */
public object TranscriptionSchema {

    private fun str() = buildJsonObject { put("type", "string") }
    private fun int() = buildJsonObject { put("type", "integer") }

    private fun enumOf(vararg values: String) = buildJsonObject {
        put("type", "string")
        putJsonArray("enum") { values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    }

    private fun arrayOf(items: JsonObject) = buildJsonObject {
        put("type", "array")
        put("items", items)
    }

    public val responseSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("language", str())
            put("type", enumOf("DRILL", "SINGLE", "DIALOGUE"))
            put("cefr", enumOf("A1", "A2", "B1", "B2", "C1", "C2"))
            put("speechStartMs", int())
            put("speechEndMs", int())
            put(
                "sentences",
                arrayOf(
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            put("index", int())
                            put("text", str())
                            put("translationKo", str())
                            put("transliterationKo", str())
                            put("startMs", int())
                            put("endMs", int())
                            put("keywords", arrayOf(str()))
                            put(
                                "breathGroups",
                                arrayOf(
                                    buildJsonObject {
                                        put("type", "object")
                                        putJsonObject("properties") {
                                            put("text", str())
                                            put("startMs", int())
                                            put("endMs", int())
                                        }
                                        putJsonArray("required") {
                                            add(kotlinx.serialization.json.JsonPrimitive("text"))
                                            add(kotlinx.serialization.json.JsonPrimitive("startMs"))
                                            add(kotlinx.serialization.json.JsonPrimitive("endMs"))
                                        }
                                    },
                                ),
                            )
                        }
                        putJsonArray("required") {
                            listOf("index", "text", "translationKo", "startMs", "endMs", "keywords")
                                .forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                        }
                    },
                ),
            )
            put(
                "expressions",
                arrayOf(
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            put("text", str())
                            put("meaningKo", str())
                        }
                    },
                ),
            )
            put(
                "warnings",
                arrayOf(enumOf("MUSIC_HEAVY", "MULTIPLE_SPEAKERS", "FAST_SPEECH", "LOW_CONFIDENCE")),
            )
        }
        putJsonArray("required") {
            listOf("language", "type", "cefr", "speechStartMs", "speechEndMs", "sentences")
                .forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
        }
    }
}
