package com.myna.transcribe

import android.util.Log
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
    data class Success(val accepted: ValidationResult.Accepted) : TranscribeResult
    data class Rejected(val reason: RejectionReason) : TranscribeResult
    data class Failed(val message: String) : TranscribeResult
    data object NoApiKey : TranscribeResult
}

/**
 * 유튜브 영상을 전사해 문장을 채운다. REQUIREMENTS §F-1.
 *
 * 모델 응답을 그대로 믿지 않는다 — [TranscriptValidator]를 반드시 거친다
 * (TRANSCRIPTION_SCHEMA §4.1). 손으로 적은 문장도 같은 검증을 통과하므로, 두 입력 경로가
 * 같은 보장을 갖는다.
 */
internal class GeminiTranscriber(private val keys: ApiKeyStore) {

    suspend fun transcribeYouTube(videoId: String): TranscribeResult = withContext(Dispatchers.IO) {
        val key = keys.geminiKey ?: return@withContext TranscribeResult.NoApiKey
        val model = resolveModel(key)
            ?: return@withContext TranscribeResult.Failed("쓸 수 있는 모델을 찾지 못했습니다.")

        // TRANSCRIPTION_SCHEMA §4.3 — 파싱 실패는 1회만 재요청한다. 낮은 temperature로.
        val attempts = listOf(
            GeminiExchange.requestForYouTube(videoId),
            GeminiExchange.retryRequestForYouTube(videoId),
        )

        var lastError: String? = null
        for ((index, body) in attempts.withIndex()) {
            val response = post("$BASE/models/$model:generateContent", key, body)
                ?: run { lastError = "네트워크에 닿지 못했습니다."; return@withContext TranscribeResult.Failed(lastError!!) }

            GeminiExchange.errorMessage(response)?.let { message ->
                // 키나 모델 문제는 재요청해도 같다. 바로 알린다.
                return@withContext TranscribeResult.Failed(message)
            }

            val raw = GeminiExchange.parseTranscript(response, videoId)
            if (raw == null) {
                lastError = "응답을 읽지 못했습니다."
                Log.w(TAG, "전사 응답 파싱 실패 (시도 ${index + 1})")
                continue
            }
            return@withContext validate(raw)
        }
        TranscribeResult.Failed(lastError ?: "전사에 실패했습니다.")
    }

    private fun validate(raw: RawTranscript): TranscribeResult =
        when (val result = TranscriptValidator.validate(raw)) {
            is ValidationResult.Accepted -> TranscribeResult.Success(result)
            is ValidationResult.Rejected -> TranscribeResult.Rejected(result.reason)
        }

    /**
     * 쓸 모델을 정한다. 설정 지정값 → 캐시 → 목록 조회 순.
     *
     * 이름을 코드에 박지 않는 이유는 [GeminiModel]에 적었다. 조회 결과는 캐시해 둔다 —
     * 전사할 때마다 목록을 받으면 그만큼 느려진다.
     */
    private fun resolveModel(key: String): String? {
        keys.modelOverride?.let { return it }
        keys.resolvedModel?.let { return it }

        val body = get("$BASE/models", key) ?: return null
        return GeminiModel.pickForVideo(body)?.also { keys.resolvedModel = it }
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
        const val TAG = "GeminiTranscriber"
    }
}
