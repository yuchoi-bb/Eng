package com.myna.transcribe

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.myna.core.transcribe.GeminiErrorKind
import com.myna.core.model.VideoSource
import com.myna.core.transcribe.GeminiExchange
import com.myna.core.transcribe.GeminiFile
import com.myna.core.transcribe.GeminiFiles
import com.myna.core.transcribe.GeminiModel
import com.myna.core.transcribe.TranscriptionPrompt
import com.myna.core.transcript.RawTranscript
import com.myna.core.transcript.RejectionReason
import com.myna.core.transcript.TranscriptValidator
import com.myna.core.transcript.ValidationResult
import com.myna.data.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 전사 결과. 실패도 값으로 돌려준다 — 사용자에게 무엇이 잘못됐는지 말해 줘야 한다. */
internal sealed interface TranscribeResult {
    data class Success(val accepted: ValidationResult.Accepted, val model: String) : TranscribeResult
    data class Rejected(val reason: RejectionReason) : TranscribeResult
    data class Failed(val message: String) : TranscribeResult
    data object NoApiKey : TranscribeResult
}

/**
 * 영상을 전사해 문장을 채운다. 유튜브(§F-1)는 URL로, 기기 영상(§F-2)은 파일을 올려서.
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

    /**
     * 기기에서 고른 영상을 전사한다.
     *
     * 유튜브와 달리 모델이 가져갈 URL이 없다. Files API로 먼저 올리고, 처리가 끝나기를
     * 기다린 뒤 그 파일로 전사한다. 영상 속 자막도 화면으로 함께 보게 한다
     * ([TranscriptionPrompt.forUploadedVideo]). 다 쓰면 올린 파일을 지운다.
     *
     * @param onProgress 올리는 중·처리 중·받아 적는 중. 쇼츠라도 몇십 초가 걸린다 —
     *   아무 표시 없이 기다리게 하면 멈춘 줄 안다.
     */
    suspend fun transcribeUpload(
        context: Context,
        videoUri: Uri,
        onProgress: (String) -> Unit,
    ): TranscribeResult = withContext(Dispatchers.IO) {
        val key = keys.geminiKey ?: return@withContext TranscribeResult.NoApiKey
        val resolver = context.contentResolver
        val mimeType = resolver.getType(videoUri)?.takeIf { it.startsWith("video/") } ?: "video/mp4"

        // 크기를 알아야 올릴 수 있다. 모르면 캐시에 한 번 복사해 잰다.
        var tempCopy: File? = null
        val size = querySize(resolver, videoUri) ?: run {
            val copy = File(context.cacheDir, "upload-${UUID.randomUUID()}")
            val copied = runCatching {
                resolver.openInputStream(videoUri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
            }.getOrNull()
            if (copied == null) return@withContext TranscribeResult.Failed("영상을 읽지 못했습니다.")
            tempCopy = copy
            copy.length()
        }
        if (size > GeminiFiles.MAX_UPLOAD_BYTES) {
            tempCopy?.delete()
            return@withContext TranscribeResult.Failed(
                "영상이 너무 큽니다 (${size / MB}MB). ${GeminiFiles.MAX_UPLOAD_BYTES / MB}MB 이하만 분석할 수 있습니다.",
            )
        }

        val copy = tempCopy
        val open = { copy?.inputStream() ?: resolver.openInputStream(videoUri) }
        val result = runCatching {
            upload(key, size, mimeType, open) { percent -> onProgress("영상을 올리는 중… $percent%") }
        }
        copy?.delete()
        val uploaded = result.getOrNull()
            ?: return@withContext TranscribeResult.Failed("영상을 올리지 못했습니다. 연결을 확인해 주세요.")

        try {
            onProgress("영상을 준비하는 중…")
            val ready = waitUntilActive(key, uploaded)
                ?: return@withContext TranscribeResult.Failed("Gemini가 영상을 처리하지 못했습니다.")

            onProgress("영상을 보고 들으며 받아 적는 중…")
            // 이 영상의 계획 ID가 된다(UP_…). 기기 영상은 고유 번호가 없으므로 여기서 만든다.
            val sourceRef = UUID.randomUUID().toString()
            val candidates = fileModelCandidates(key)
            if (candidates.isEmpty()) {
                return@withContext TranscribeResult.Failed(
                    "영상을 받아 줄 모델을 찾지 못했습니다. 설정에서 모델 이름을 직접 넣어 보세요.",
                )
            }
            val fileMime = ready.mimeType ?: mimeType
            for (model in candidates) {
                val outcome = attempt(
                    model = model,
                    key = key,
                    bodies = listOf(
                        GeminiExchange.requestForUploadedVideo(ready.uri, fileMime),
                        GeminiExchange.requestForUploadedVideo(ready.uri, fileMime, retry = true),
                    ),
                    parse = { GeminiExchange.parseTranscript(it, VideoSource.UPLOAD, sourceRef) },
                    rememberModel = false,
                )
                when (outcome) {
                    is Attempt.Done -> return@withContext outcome.result
                    is Attempt.TryNextModel -> Log.i(TAG, "$model 이(가) 영상 파일을 거절했습니다: ${outcome.message}")
                }
            }
            TranscribeResult.Failed("시도한 모델이 모두 영상 파일을 받지 않았습니다. 설정에서 모델 이름을 직접 넣어 보세요.")
        } finally {
            // 48시간 뒤 스스로 지워지지만, 다 쓴 영상을 남겨 둘 이유가 없다.
            runCatching { connect("$BASE/${uploaded.name}", key, "DELETE").use { it.responseCode } }
        }
    }

    private fun querySize(resolver: ContentResolver, uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else null
        }
    }.getOrNull()?.takeIf { it > 0 }

    /**
     * 재개 가능 업로드. 시작 요청으로 업로드 URL을 받고, 그 URL로 바이트를 보내며 마무리한다.
     */
    private fun upload(
        key: String,
        size: Long,
        mimeType: String,
        open: () -> InputStream?,
        onPercent: (Int) -> Unit,
    ): GeminiFile? = runCatching {
        val uploadUrl = (URL(GeminiFiles.UPLOAD_URL).openConnection() as HttpURLConnection).use { start ->
            start.requestMethod = "POST"
            start.setRequestProperty("x-goog-api-key", key)
            start.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            start.setRequestProperty("X-Goog-Upload-Command", "start")
            start.setRequestProperty("X-Goog-Upload-Header-Content-Length", size.toString())
            start.setRequestProperty("X-Goog-Upload-Header-Content-Type", mimeType)
            start.setRequestProperty("Content-Type", "application/json")
            start.connectTimeout = CONNECT_TIMEOUT_MS
            start.readTimeout = CONNECT_TIMEOUT_MS
            start.doOutput = true
            start.outputStream.bufferedWriter().use { it.write(GeminiFiles.startBody("myna-upload").toString()) }
            if (start.responseCode !in 200..299) {
                Log.w(TAG, "업로드 시작 실패 ${start.responseCode}: ${start.readBody()}")
                return@runCatching null
            }
            start.getHeaderField("X-Goog-Upload-URL") ?: return@runCatching null
        }

        (URL(uploadUrl).openConnection() as HttpURLConnection).use { put ->
            put.requestMethod = "POST"
            put.setRequestProperty("X-Goog-Upload-Offset", "0")
            put.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
            put.setFixedLengthStreamingMode(size)
            put.connectTimeout = CONNECT_TIMEOUT_MS
            put.readTimeout = READ_TIMEOUT_MS
            put.doOutput = true
            val input = open() ?: return@runCatching null
            input.use { source ->
                put.outputStream.use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        sent += read
                        val percent = (sent * 100 / size).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onPercent(percent)
                        }
                    }
                }
            }
            val body = put.readBody()
            GeminiFiles.parseFile(body).also { if (it == null) Log.w(TAG, "업로드 응답을 읽지 못했습니다: $body") }
        }
    }.onFailure { Log.w(TAG, "업로드 실패", it) }.getOrNull()

    /** 영상은 올린 뒤 처리가 끝나야 쓸 수 있다. 쇼츠는 대개 몇 초, 길면 1분 남짓이다. */
    private suspend fun waitUntilActive(key: String, file: GeminiFile): GeminiFile? {
        var current = file
        repeat(POLL_LIMIT) {
            when (current.state) {
                GeminiFile.State.ACTIVE -> return current
                GeminiFile.State.FAILED -> return null
                else -> Unit
            }
            delay(POLL_INTERVAL_MS)
            current = get("$BASE/${file.name}", key)?.let(GeminiFiles::parseFile) ?: current
        }
        return current.takeIf { it.state == GeminiFile.State.ACTIVE }
    }

    /**
     * 기기 영상에 쓸 모델. 유튜브 URL을 거절한 이력은 여기서 따지지 않는다 — 파일 입력은
     * 대부분의 모델이 받는다.
     */
    private fun fileModelCandidates(key: String): List<String> {
        keys.modelOverride?.let { return listOf(it) }
        val discovered = get("$BASE/models", key)
            ?.let(GeminiModel::candidatesForVideo)
            .orEmpty()
        return (listOfNotNull(keys.resolvedModel) + discovered).distinct().take(MAX_ATTEMPTS)
    }

    private sealed interface Attempt {
        data class Done(val result: TranscribeResult) : Attempt
        data class TryNextModel(val message: String) : Attempt
    }

    private fun attempt(model: String, key: String, videoId: String): Attempt = attempt(
        model = model,
        key = key,
        // TRANSCRIPTION_SCHEMA §4.3 — 파싱 실패는 temperature를 낮춰 1회만 재요청한다.
        bodies = listOf(
            GeminiExchange.requestForYouTube(videoId),
            GeminiExchange.retryRequestForYouTube(videoId),
        ),
        parse = { GeminiExchange.parseTranscript(it, videoId) },
        rememberModel = true,
    )

    /**
     * @param rememberModel 통한 모델을 다음 유튜브 전사의 첫 후보로 기억할지. 기기 영상은 파일로
     *   올리므로 유튜브 URL을 못 받는 모델도 통한다 — 그 모델을 유튜브 쪽 첫 후보로 올리면 안 된다.
     */
    private fun attempt(
        model: String,
        key: String,
        bodies: List<JsonObject>,
        parse: (String) -> RawTranscript?,
        rememberModel: Boolean,
    ): Attempt {

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

            val raw = parse(response)
            if (raw == null) {
                lastProblem = "응답을 읽지 못했습니다."
                Log.w(TAG, "$model 응답 파싱 실패 (시도 ${index + 1})")
                continue
            }
            // 통했다. 다음부터는 이 모델로 바로 간다.
            if (rememberModel) keys.resolvedModel = model
            return Attempt.Done(validate(raw, model))
        }
        return Attempt.Done(TranscribeResult.Failed(lastProblem))
    }

    private fun validate(raw: RawTranscript, model: String): TranscribeResult =
        when (val result = TranscriptValidator.validate(raw)) {
            is ValidationResult.Accepted ->
                TranscribeResult.Success(result, model)
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

        const val POLL_INTERVAL_MS = 2_000L
        /** 2초 × 90 = 3분. 쇼츠가 이보다 오래 걸리면 다른 문제다. */
        const val POLL_LIMIT = 90
        const val MB = 1024 * 1024

        /** 거절 한 번이 수십 초다. 무한정 훑지 않는다. */
        const val MAX_ATTEMPTS = 4
        const val TAG = "GeminiTranscriber"
    }
}
