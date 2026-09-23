package com.myna.transcribe

import android.util.Log
import com.myna.core.transcribe.GeminiErrorKind
import com.myna.core.transcribe.GeminiExchange
import com.myna.core.transcribe.GeminiModel
import com.myna.core.transcript.RawTranscript
import com.myna.core.transcript.RejectionReason
import com.myna.core.transcript.TranscriptValidator
import com.myna.core.transcript.ValidationResult
import com.myna.data.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import java.net.URL

/** 전사 결과. 실패도 값으로 돌려준다 — 사용자에게 무엇이 잘못됐는지 말해 줘야 한다. */
internal sealed interface TranscribeResult {
    data class Success(val accepted: ValidationResult.Accepted, val model: String) : TranscribeResult
    data class Rejected(val reason: RejectionReason) : TranscribeResult
    data class Failed(val message: String) : TranscribeResult
    data object NoApiKey : TranscribeResult
}

/**
 * 유튜브 영상을 전사해 문장을 채운다. REQUIREMENTS §F-1.
 *
 * **어떤 모델이 영상을 받는지 미리 알 수 없다.** `models.list` 응답에 그 정보가 없기
 * 때문이다. 그래서 후보를 순서대로 눌러 보고, 통한 모델을 기억한다. 거절한 모델도
 * 기억해 두 번 누르지 않는다.
 *
 * 모델 응답은 그대로 믿지 않는다 — [TranscriptValidator]를 반드시 거친다
 * (TRANSCRIPTION_SCHEMA §4.1).
 */
internal class GeminiTranscriber(private val keys: ApiKeyStore) {

    suspend fun transcribeYouTube(videoId: String): TranscribeResult = withContext(Dispatchers.IO) {
        val key = keys.geminiKey ?: return@withContext TranscribeResult.NoApiKey

        val candidates = modelCandidates(key)
        if (candidates.isEmpty()) {
            return@withContext TranscribeResult.Failed(
                "영상을 받아 줄 모델을 찾지 못했습니다. 설정에서 모델 이름을 직접 넣어 보세요.",
            )
        }

        val refused = mutableListOf<String>()
        for (model in candidates) {
            when (val outcome = attempt(model, key, videoId)) {
                is Attempt.Done -> return@withContext outcome.result
                is Attempt.TryNextModel -> {
                    // 이 모델은 영상을 못 받는다. 기억해 두고 다음으로.
                    keys.markUnsupported(model)
                    refused += model
                    Log.i(TAG, "$model 이(가) 영상 입력을 거절했습니다: ${outcome.message}")
                }
            }
        }

        TranscribeResult.Failed(
            "시도한 모델이 모두 영상 입력을 받지 않았습니다 (${refused.joinToString()}). " +
                "설정에서 모델 이름을 직접 넣어 보세요.",
        )
    }

    private sealed interface Attempt {
        data class Done(val result: TranscribeResult) : Attempt
        data class TryNextModel(val message: String) : Attempt
    }

    private fun attempt(model: String, key: String, videoId: String): Attempt {
        // TRANSCRIPTION_SCHEMA §4.3 — 파싱 실패는 temperature를 낮춰 1회만 재요청한다.
        val bodies = listOf(
            GeminiExchange.requestForYouTube(videoId),
            GeminiExchange.retryRequestForYouTube(videoId),
        )

        var lastProblem = "전사에 실패했습니다."
        for ((index, body) in bodies.withIndex()) {
            val response = post("$BASE/models/$model:generateContent", key, body)
                ?: return Attempt.Done(TranscribeResult.Failed("네트워크에 닿지 못했습니다."))

            val error = GeminiExchange.errorMessage(response)
            if (error != null) {
                return when (GeminiModel.classifyError(error)) {
                    // 모델을 바꾸면 통할 수 있다.
                    GeminiErrorKind.MODEL_CAPABILITY -> Attempt.TryNextModel(error)
                    // 키와 한도는 모델을 바꿔도 같다. 계속 누르면 시간과 한도만 버린다.
                    GeminiErrorKind.AUTH -> Attempt.Done(
                        TranscribeResult.Failed("API 키를 확인해 주세요. ($error)"),
                    )
                    GeminiErrorKind.QUOTA -> Attempt.Done(
                        TranscribeResult.Failed("사용 한도를 넘었습니다. 잠시 뒤 다시 시도해 주세요."),
                    )
                    GeminiErrorKind.OTHER -> Attempt.Done(TranscribeResult.Failed(error))
                }
            }

            val raw = GeminiExchange.parseTranscript(response, videoId)
            if (raw == null) {
                lastProblem = "응답을 읽지 못했습니다."
                Log.w(TAG, "$model 응답 파싱 실패 (시도 ${index + 1})")
                continue
            }
            // 통했다. 다음부터는 이 모델로 바로 간다.
            keys.resolvedModel = model
            return Attempt.Done(validate(raw))
        }
        return Attempt.Done(TranscribeResult.Failed(lastProblem))
    }

    private fun validate(raw: RawTranscript): TranscribeResult =
        when (val result = TranscriptValidator.validate(raw)) {
            is ValidationResult.Accepted ->
                TranscribeResult.Success(result, keys.resolvedModel.orEmpty())
            is ValidationResult.Rejected -> TranscribeResult.Rejected(result.reason)
        }

    /**
     * 시도할 모델 순서. 직접 지정 → 지난번 성공 → 목록 조회 순으로 앞자리를 준다.
     *
     * 거절 이력이 있는 모델은 뺀다. 빼고 나면 아무것도 안 남는 경우가 있는데, 그때는
     * 이력을 무시하고 전부 다시 시도한다 — 모델 쪽 사정이 바뀌었을 수 있다.
     */
    private fun modelCandidates(key: String): List<String> {
        keys.modelOverride?.let { return listOf(it) }

        val discovered = get("$BASE/models", key)
            ?.let(GeminiModel::candidatesForVideo)
            .orEmpty()
        val ordered = (listOfNotNull(keys.resolvedModel) + discovered).distinct()

        val fresh = ordered.filterNot { it in keys.unsupportedModels }
        return fresh.ifEmpty { ordered }.take(MAX_ATTEMPTS)
    }

    private fun get(url: String, key: String): String? = runCatching {
        connect(url, key, "GET").use { it.readBody() }
    }.onFailure { Log.w(TAG, "GET 실패: $url", it) }.getOrNull()

    private fun post(url: String, key: String, body: JsonObject): String? = runCatching {
        connect(url, key, "POST").use { connection ->
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            connection.readBody()
        }
    }.onFailure { Log.w(TAG, "POST 실패: $url", it) }.getOrNull()

    private fun connect(url: String, key: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("x-goog-api-key", key)
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = CONNECT_TIMEOUT_MS
            // 영상 한 편을 모델이 처음부터 끝까지 본다. 짧게 잡으면 정상 요청이 끊긴다.
            readTimeout = READ_TIMEOUT_MS
        }

    /** 오류 응답도 본문을 읽는다 — 거기에 사용자에게 보여 줄 메시지가 있다. */
    private fun HttpURLConnection.readBody(): String =
        (if (responseCode in 200..299) inputStream else errorStream)
            ?.bufferedReader()?.use { it.readText() }
            ?: ""

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }

    private companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 180_000

        /** 거절 한 번이 수십 초다. 무한정 훑지 않는다. */
        const val MAX_ATTEMPTS = 4
        const val TAG = "GeminiTranscriber"
    }
}
