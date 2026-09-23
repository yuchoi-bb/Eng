package com.myna.core.transcribe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 쓸 모델을 고른다.
 *
 * **모델 이름을 코드에 박지 않는다.** Gemini의 모델 이름은 자주 바뀌고, 박아 두면 이름이
 * 바뀌는 날 앱이 조용히 죽는다. 대신 `models.list`가 돌려주는 목록에서 조건에 맞는 것을
 * 고른다 — 이름이 바뀌어도 따라간다.
 */
public object GeminiModel {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 영상 전사에 쓸 모델을 고른다.
     *
     * 고르는 기준은 셋이다.
     * 1. `generateContent`를 지원할 것 — 그래야 요청을 받는다
     * 2. 이름에 `flash`가 들어갈 것 — 영상 한 편 전사에 pro급은 과하고 느리다
     * 3. 미리보기·실험 딱지가 붙지 않을 것 — 예고 없이 사라진다
     *
     * 그중 이름이 사전순으로 가장 뒤인 것을 고른다. 대개 새 버전이 뒤에 오지만 완벽하지는
     * 않다 — `3.10`은 `3.9`보다 앞선다. 설정에서 모델을 직접 지정할 수 있게 열어 둔 이유다.
     */
    public fun pickForVideo(modelsListBody: String): String? = runCatching {
        val candidates = json.parseToJsonElement(modelsListBody).jsonObject["models"]
            ?.jsonArray
            ?.mapNotNull { element ->
                val model = element.jsonObject
                val name = model["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val methods = model["supportedGenerationMethods"]?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    .orEmpty()
                if ("generateContent" !in methods) return@mapNotNull null
                name.removePrefix("models/")
            }
            .orEmpty()

        val usable = candidates.filter { it.contains("flash", ignoreCase = true) }
        val stable = usable.filterNot { name ->
            UNSTABLE_MARKERS.any { name.contains(it, ignoreCase = true) }
        }
        (stable.ifEmpty { usable }).maxOrNull()
    }.getOrNull()

    private val UNSTABLE_MARKERS = listOf("preview", "exp", "thinking", "tts", "image", "live")
}
