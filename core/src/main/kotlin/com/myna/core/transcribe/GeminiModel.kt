package com.myna.core.transcribe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Gemini가 요청을 거절한 이유. 다음에 무엇을 할지가 여기서 갈린다. */
public enum class GeminiErrorKind {
    /** 이 모델이 영상 입력을 못 받는다. **다음 후보로 넘어가면 된다.** */
    MODEL_CAPABILITY,

    /** 키가 잘못됐다. 모델을 바꿔도 같다. */
    AUTH,

    /** 한도를 넘었다. 잠시 뒤 다시. */
    QUOTA,

    /** 그 외. 사용자에게 그대로 보여 준다. */
    OTHER,
}

/**
 * 쓸 모델을 고른다.
 *
 * **모델 이름을 코드에 박지 않는다.** 이름은 자주 바뀌고, 박아 두면 바뀌는 날 앱이
 * 조용히 죽는다.
 *
 * 그런데 `models.list` 응답에는 **어떤 모델이 유튜브 영상을 받는지가 없다.**
 * `supportedGenerationMethods`는 `generateContent` 같은 호출 방식만 말해 줄 뿐이다.
 * 이름으로 짐작하려 들면 또 다른 추측일 뿐이고, 실제로 `gemini-omni-1.1-flash`가
 * 그 짐작을 통과한 뒤 "YouTube video input is not supported by the model"로 거절했다.
 *
 * 그래서 **하나를 고르지 않고 순서 있는 후보 목록을 준다.** 호출자는 거절당하면 다음으로
 * 넘어가고, 통한 모델을 기억한다. 추측 대신 확인이다.
 */
public object GeminiModel {

    private val json = Json { ignoreUnknownKeys = true }

    /** 영상을 다룰 리 없는 모델. 호출해 볼 가치도 없다. */
    private val NEVER = listOf("embedding", "embed", "aqa", "tts", "imagen", "image-generation", "live")

    /** 예고 없이 사라지는 이름들. 쓸 수는 있으나 뒤로 민다. */
    private val UNSTABLE = listOf("preview", "-exp", "experimental")

    /** 특수 목적이거나 작아서 영상 처리가 빠질 법한 것들. 역시 뒤로 민다. */
    private val SPECIALIZED = listOf("omni", "lite", "nano", "-8b", "thinking", "learnlm", "gemma")

    /**
     * 시도할 순서대로 후보를 돌려준다.
     *
     * 앞쪽일수록 영상 전사에 맞을 가능성이 높다 — flash 계열, 안정판, 최신 순.
     * 하나도 안 통하면 목록이 곧 사용자에게 보여 줄 "이만큼 시도했다"가 된다.
     */
    public fun candidatesForVideo(modelsListBody: String): List<String> = runCatching {
        json.parseToJsonElement(modelsListBody).jsonObject["models"]
            ?.jsonArray
            ?.mapNotNull { element ->
                val model = element.jsonObject
                val name = model["name"]?.jsonPrimitive?.content?.removePrefix("models/")
                    ?: return@mapNotNull null
                val methods = model["supportedGenerationMethods"]?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    .orEmpty()
                if ("generateContent" !in methods) return@mapNotNull null
                if (NEVER.any { name.contains(it, ignoreCase = true) }) return@mapNotNull null
                name
            }
            ?.distinct()
            ?.sortedWith(
                compareBy<String> { if (it.contains("flash", ignoreCase = true)) 0 else 1 }
                    .thenBy { if (SPECIALIZED.any { m -> it.contains(m, true) }) 1 else 0 }
                    .thenBy { if (UNSTABLE.any { m -> it.contains(m, true) }) 1 else 0 }
                    .thenByDescending { it },
            )
            .orEmpty()
    }.getOrElse { emptyList() }

    /**
     * 거절 사유를 분류한다.
     *
     * 모델 능력 문제면 다음 후보로 넘어가고, 키나 한도 문제면 멈춘다 — 모델을 바꿔도
     * 같은 답이 오므로 계속 호출하면 시간과 한도만 버린다.
     */
    public fun classifyError(message: String?): GeminiErrorKind {
        val text = message?.lowercase() ?: return GeminiErrorKind.OTHER
        return when {
            "not supported" in text || "is not found" in text || "unsupported" in text ->
                GeminiErrorKind.MODEL_CAPABILITY

            "api key" in text || "permission" in text || "unauthenticated" in text ->
                GeminiErrorKind.AUTH

            "quota" in text || "rate limit" in text || "resource_exhausted" in text ->
                GeminiErrorKind.QUOTA

            else -> GeminiErrorKind.OTHER
        }
    }
}
