package com.myna.ui.entry

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.myna.core.model.Sentence
import com.myna.core.model.TimestampUnit
import com.myna.core.model.Transcript
import com.myna.core.model.VideoPlan
import com.myna.core.model.VideoPlanId
import com.myna.core.model.VideoSource
import com.myna.core.playback.SegmentPadding
import com.myna.core.plan.PlanEdit
import com.myna.core.session.RepsCalculator
import com.myna.core.source.YouTubeUrl
import com.myna.core.transcript.RawBreathGroup
import com.myna.core.transcript.RawExpression
import com.myna.core.transcript.RawSentence
import com.myna.core.transcript.RawTranscript
import com.myna.core.transcript.TranscriptValidator
import com.myna.core.transcript.ValidationResult
import com.myna.media.SourcePlayback
import com.myna.transcribe.TranscribeResult
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * 편집 중인 문장 하나.
 *
 * @param origin 분석 결과나 저장된 문장. 저장할 때 한글 발음·키워드·호흡 조각을 이어받는다 —
 *   입력칸에 없는 값이라 그대로 두면 사라진다. 저장된 영상을 고칠 때는 원래 번호도 여기서 온다.
 */
private class SentenceDraft(val key: Int, val origin: Sentence? = null) {
    var text by mutableStateOf(origin?.text.orEmpty())
    var translationKo by mutableStateOf(origin?.translationKo.orEmpty())
    var startSec by mutableStateOf(origin?.let { secondsText(it.startMs) }.orEmpty())
    var endSec by mutableStateOf(origin?.let { secondsText(it.endMs) }.orEmpty())

    val startMs: Int? get() = startSec.trim().toDoubleOrNull()?.let { (it * 1000).toInt() }
    val endMs: Int? get() = endSec.trim().toDoubleOrNull()?.let { (it * 1000).toInt() }
}

/** LazyColumn 항목 열쇠. 지우고 되돌려도 같은 문장이 같은 열쇠를 가진다. */
private class DraftKeys {
    private var next = 0
    fun create(origin: Sentence? = null) = SentenceDraft(next++, origin)
}

/**
 * 영상 추가 + 문장 입력·편집. TRANSCRIPTION_SCHEMA §4.3.
 *
 * 영상은 두 경로로 들어온다.
 * - **유튜브 링크** (F-1) — 쇼츠 URL을 붙여 넣거나 유튜브 앱에서 공유한다
 * - **기기 영상** (F-2) — Photo Picker로 고른다
 *
 * 자동 분석 결과에는 인사말·구독 요청처럼 따라 할 필요 없는 문장이 섞이고, 문장 경계가
 * 조금씩 어긋난다. 그래서 문장마다 **삭제**와 **미리듣기**를 두고, 시작·끝을 0.5초씩
 * 옮기면 바로 다시 들려준다.
 *
 * 사람이 적은 값도 [TranscriptValidator]를 그대로 통과시킨다. 검증을 건너뛰면 시각이
 * 뒤집힌 문장이나 문장에 없는 키워드가 그대로 저장된다.
 *
 * @param editing 저장된 영상의 문장을 고칠 때. 영상은 바꿀 수 없고, 외운 문장과 이어서 하기
 *   자리는 [PlanEdit]이 새 번호로 옮긴다.
 */
@Composable
internal fun ManualEntryScreen(
    initialVideoUri: Uri?,
    initialYouTubeVideoId: String?,
    dailyTargetSec: Int,
    hasApiKey: Boolean,
    onTranscribe: suspend (videoId: String) -> TranscribeResult,
    onOpenApiKeySettings: () -> Unit,
    onCancel: () -> Unit,
    onSaved: (VideoPlan, String?) -> Unit,
    editing: VideoPlan? = null,
    onEdited: (plan: VideoPlan, originOfNew: List<Int?>) -> Unit = { _, _ -> },
    /** 기기 영상 전사. 올리는 중·처리 중 같은 진행 상황을 두 번째 인자로 알린다. */
    onTranscribeUpload: suspend (videoUri: Uri, onProgress: (String) -> Unit) -> TranscribeResult =
        { _, _ -> TranscribeResult.NoApiKey },
) {
    val scope = rememberCoroutineScope()
    var transcribing by remember { mutableStateOf(false) }
    var videoUri by remember { mutableStateOf(initialVideoUri) }
    var youTubeVideoId by remember { mutableStateOf(initialYouTubeVideoId) }
    var linkInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val keys = remember { DraftKeys() }
    val drafts = remember {
        mutableStateListOf<SentenceDraft>().apply {
            val saved = editing?.sentences
            if (saved.isNullOrEmpty()) add(keys.create()) else saved.forEach { add(keys.create(it)) }
        }
    }
    // 분석 결과나 저장된 전사. 유형·수준·표현처럼 입력칸에 없는 값을 저장할 때 이어받는다.
    var baseTranscript by remember { mutableStateOf(editing?.transcript) }
    // 어느 기기 영상을 분석한 결과인가. 분석 뒤 다른 영상을 고르면 그 결과를 쓰지 않는다.
    var analyzedUri by remember { mutableStateOf<Uri?>(null) }
    // 기기 영상은 올리고 처리하는 데 시간이 걸린다. 지금 무엇을 하는지 보여 준다.
    var progressText by remember { mutableStateOf<String?>(null) }

    // 방금 지운 문장. 되돌릴 수 있게 한 번 들고 있는다.
    var lastDeleted by remember { mutableStateOf<Pair<Int, SentenceDraft>?>(null) }

    // 미리듣기
    var playback by remember { mutableStateOf<SourcePlayback?>(null) }
    var playingKey by remember { mutableStateOf<Int?>(null) }
    val timestampUnit = baseTranscript?.timestampUnit
        ?: if (youTubeVideoId != null) TimestampUnit.SECOND else TimestampUnit.MILLISECOND

    fun stopPreview() {
        playback?.pause()
        playingKey = null
    }

    fun preview(draft: SentenceDraft) {
        val start = draft.startMs
        val end = draft.endMs
        if (start == null || end == null || start >= end) {
            error = "문장 ${drafts.indexOf(draft) + 1}의 시작·끝 시각을 확인해 주세요."
            return
        }
        val player = playback ?: run {
            error = "영상을 불러오는 중입니다. 잠시 후 다시 눌러 주세요."
            return
        }
        error = null
        // 세션과 같은 구간을 들려준다 — 유튜브는 앞뒤로 여유를 붙여 재생한다.
        val segment = SegmentPadding.segmentFor(
            Sentence(index = 0, text = draft.text, translationKo = "", startMs = start, endMs = end, keywords = emptyList()),
            timestampUnit,
        )
        playingKey = draft.key
        player.playSegment(segment, 1f) {
            if (playingKey == draft.key) playingKey = null
        }
    }

    fun nudge(draft: SentenceDraft, start: Boolean, deltaSec: Double) {
        val field = if (start) draft.startSec else draft.endSec
        val value = field.trim().toDoubleOrNull() ?: return
        val moved = secondsText(((value + deltaSec).coerceAtLeast(0.0) * 1000).toInt())
        if (start) draft.startSec = moved else draft.endSec = moved
        // 옮기자마자 들어 봐야 맞았는지 안다.
        preview(draft)
    }

    fun delete(draft: SentenceDraft) {
        if (playingKey == draft.key) stopPreview()
        val index = drafts.indexOf(draft)
        drafts.remove(draft)
        lastDeleted = index to draft
        if (drafts.isEmpty()) drafts.add(keys.create())
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            videoUri = uri
            youTubeVideoId = null // 두 소스를 동시에 둘 수 없다
        }
    }

    val hasSource = youTubeVideoId != null || videoUri != null

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(if (editing != null) "문장 편집" else "영상 추가", style = MaterialTheme.typography.headlineSmall)

        // 미리듣기 재생기는 목록 위에 고정한다. 목록 안에 두면 스크롤로 사라진다.
        if (hasSource) {
            Box(
                // 유튜브는 200×200px보다 작은 임베드 플레이어의 재생을 거부할 수 있다. 세로 9:16이라
                // 폭이 좁아지므로 높이를 넉넉히 둔다.
                Modifier.fillMaxWidth().height(200.dp).padding(top = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                SegmentPreviewPlayer(
                    youTubeVideoId = youTubeVideoId,
                    videoUri = videoUri,
                    onPlayback = { playback = it },
                    onRefused = { reason ->
                        playingKey = null
                        error = "$reason 미리듣기를 다시 눌러 보세요."
                    },
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(9f / 16f, matchHeightConstraintsFirst = true),
                )
            }
        }

        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            if (editing == null) {
                item {
                    SourceInputs(
                        linkInput = linkInput,
                        onLinkChange = { input ->
                            linkInput = input
                            // 붙여 넣는 즉시 인식한다. 유튜브 앱은 제목과 문구를 함께 보내므로
                            // 사용자가 URL만 골라 내게 하지 않는다.
                            YouTubeUrl.extractVideoId(input)?.let {
                                youTubeVideoId = it
                                videoUri = null
                            }
                        },
                        onPickVideo = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                        },
                        sourceLabel = sourceLabel(youTubeVideoId, videoUri),
                    )
                }

                // 자동 채우기. 유튜브는 링크로, 기기 영상은 파일을 올려서 분석한다.
                val readyVideoId = youTubeVideoId
                val readyUri = videoUri
                if (readyVideoId != null || readyUri != null) {
                    item {
                        TranscribeButton(
                            hasApiKey = hasApiKey,
                            transcribing = transcribing,
                            progressText = progressText,
                            isUpload = readyVideoId == null,
                            onOpenApiKeySettings = onOpenApiKeySettings,
                            onClick = {
                                scope.launch {
                                    transcribing = true
                                    error = null
                                    progressText = null
                                    stopPreview()
                                    val result = if (readyVideoId != null) {
                                        onTranscribe(readyVideoId)
                                    } else {
                                        onTranscribeUpload(readyUri!!) { progressText = it }
                                    }
                                    when (result) {
                                        is TranscribeResult.Success -> {
                                            val transcript = result.accepted.transcript
                                            baseTranscript = transcript
                                            analyzedUri = readyUri
                                            drafts.clear()
                                            transcript.sentences.forEach { drafts.add(keys.create(it)) }
                                            lastDeleted = null
                                            error = if (result.accepted.repairs.isNotEmpty()) {
                                                "문장을 채웠습니다. 일부를 자동으로 고쳤으니 확인해 주세요."
                                            } else {
                                                null
                                            }
                                        }
                                        is TranscribeResult.Rejected ->
                                            error = "이 영상은 쓸 수 없습니다. (${result.reason}) 직접 적어 주세요."
                                        is TranscribeResult.Failed -> error = result.message
                                        TranscribeResult.NoApiKey -> onOpenApiKeySettings()
                                    }
                                    progressText = null
                                    transcribing = false
                                }
                            },
                        )
                    }
                }
            }

            item {
                Text(
                    if (hasSource) {
                        "따라 할 필요 없는 문장(인사, 구독 요청 등)은 지우세요. ▶로 들어 보고 " +
                            "시작·끝이 어긋나면 ±0.5초로 옮기면 바로 다시 들려줍니다."
                    } else {
                        "영상에서 들리는 문장과 시각을 적어 주세요. 한 문장씩 나눠 적을수록 반복이 쉬워집니다."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            itemsIndexed(drafts, key = { _, draft -> draft.key }) { index, draft ->
                SentenceCard(
                    number = index + 1,
                    draft = draft,
                    canPreview = hasSource,
                    playing = playingKey == draft.key,
                    onPreview = { if (playingKey == draft.key) stopPreview() else preview(draft) },
                    onDelete = { delete(draft) },
                    onNudge = { start, delta -> nudge(draft, start, delta) },
                )
            }

            item {
                TextButton(onClick = { drafts.add(keys.create()) }) {
                    Text("+ 문장 추가")
                }
            }
        }

        lastDeleted?.let { (index, draft) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "문장을 지웠습니다: ${draft.text.take(24)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    // 빈 칸 하나만 남아 있었다면 그 자리를 되돌린 문장으로 채운다.
                    if (drafts.size == 1 && drafts[0].origin == null && drafts[0].text.isBlank()) drafts.clear()
                    drafts.add(index.coerceIn(0, drafts.size), draft)
                    lastDeleted = null
                }) { Text("되돌리기") }
            }
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("취소") }
            Button(
                onClick = {
                    stopPreview()
                    val youTubeId = youTubeVideoId
                    val localUri = videoUri
                    if (editing == null && youTubeId == null && localUri == null) {
                        error = "유튜브 링크를 붙여 넣거나 기기에서 영상을 골라 주세요."
                        return@Button
                    }
                    // 분석한 뒤 다른 영상으로 바꿨다면 그 분석 결과는 이 영상 것이 아니다.
                    val base = baseTranscript?.takeIf {
                        editing != null ||
                            (it.source == VideoSource.YOUTUBE && it.sourceRef == youTubeId) ||
                            (it.source == VideoSource.UPLOAD && localUri != null && analyzedUri == localUri)
                    }
                    when (val result = buildTranscript(drafts, youTubeId, base)) {
                        is BuildResult.Failure -> error = result.message
                        is BuildResult.Success -> {
                            val transcript = result.accepted.transcript
                            if (editing != null) {
                                // 새 문장 i ← 입력 칸 번호 ← 원래 문장 번호
                                val originOfNew = result.accepted.sourceIndexes.map { position ->
                                    position?.let { drafts.getOrNull(it)?.origin?.index }
                                }
                                onEdited(
                                    PlanEdit.rebase(editing, transcript, originOfNew, dailyTargetSec),
                                    originOfNew,
                                )
                            } else {
                                val suggested = RepsCalculator.suggestedReps(dailyTargetSec, transcript)
                                // 유튜브는 미디어를 저장하지 않는다 — 앱은 URL과 텍스트만 보관한다(§F-1).
                                onSaved(
                                    VideoPlan(
                                        id = VideoPlanId.of(transcript.source, transcript.sourceRef),
                                        transcript = transcript,
                                        suggestedReps = suggested,
                                        targetReps = suggested,
                                    ),
                                    localUri?.toString(),
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(if (editing != null) "저장" else "저장하고 시작")
            }
        }
    }
}

@Composable
private fun SourceInputs(
    linkInput: String,
    onLinkChange: (String) -> Unit,
    onPickVideo: () -> Unit,
    sourceLabel: String,
) {
    Column {
        OutlinedTextField(
            value = linkInput,
            onValueChange = onLinkChange,
            label = { Text("유튜브 쇼츠 링크") },
            placeholder = { Text("https://youtube.com/shorts/...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onPickVideo, modifier = Modifier.fillMaxWidth()) {
            Text("또는 기기에서 영상 고르기")
        }
        Text(sourceLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun TranscribeButton(
    hasApiKey: Boolean,
    transcribing: Boolean,
    progressText: String?,
    isUpload: Boolean,
    onOpenApiKeySettings: () -> Unit,
    onClick: () -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        if (hasApiKey) {
            Button(onClick = onClick, enabled = !transcribing, modifier = Modifier.fillMaxWidth()) {
                Text(if (transcribing) "영상을 분석하는 중…" else "문장 자동으로 채우기")
            }
            val note = when {
                transcribing && progressText != null -> progressText
                transcribing -> "영상 전체를 듣고 받아 적는 중입니다. 길이에 따라 1분 정도 걸립니다."
                // 기기 영상은 Gemini에 올린다. 모바일 데이터라면 용량을 알아 두는 편이 낫다.
                isUpload -> "영상을 Gemini에 올려 소리와 화면 자막을 함께 보고 문장·해석을 채웁니다. " +
                    "다 쓰면 올린 영상은 지웁니다."
                else -> null
            }
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        } else {
            TextButton(onClick = onOpenApiKeySettings, modifier = Modifier.fillMaxWidth()) {
                Text("문장을 자동으로 채우려면 API 키를 넣어 주세요")
            }
        }
    }
}

@Composable
private fun SentenceCard(
    number: Int,
    draft: SentenceDraft,
    canPreview: Boolean,
    playing: Boolean,
    onPreview: () -> Unit,
    onDelete: () -> Unit,
    onNudge: (start: Boolean, deltaSec: Double) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("문장 $number", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (canPreview) {
                    TextButton(onClick = onPreview) { Text(if (playing) "■ 멈춤" else "▶ 미리듣기") }
                }
                TextButton(onClick = onDelete) {
                    Text("삭제", color = MaterialTheme.colorScheme.error)
                }
            }
            OutlinedTextField(
                value = draft.text,
                onValueChange = { draft.text = it },
                label = { Text("영어 문장") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.translationKo,
                onValueChange = { draft.translationKo = it },
                label = { Text("한글 뜻") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth()) {
                TimeField(
                    label = "시작(초)",
                    value = draft.startSec,
                    onValueChange = { draft.startSec = it },
                    imeAction = ImeAction.Next,
                    canNudge = canPreview,
                    onNudge = { onNudge(true, it) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TimeField(
                    label = "끝(초)",
                    value = draft.endSec,
                    onValueChange = { draft.endSec = it },
                    imeAction = ImeAction.Done,
                    canNudge = canPreview,
                    onNudge = { onNudge(false, it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TimeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    imeAction: ImeAction,
    canNudge: Boolean,
    onNudge: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
            modifier = Modifier.fillMaxWidth(),
        )
        if (canNudge) {
            // 손가락으로 소수점을 고치기는 번거롭다. 0.5초씩 옮기고 바로 들어 본다.
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { onNudge(-NUDGE_SEC) }, modifier = Modifier.weight(1f)) { Text("−0.5") }
                TextButton(onClick = { onNudge(NUDGE_SEC) }, modifier = Modifier.weight(1f)) { Text("+0.5") }
            }
        }
    }
}

private const val NUDGE_SEC = 0.5

/** 밀리초를 입력칸에 넣을 초 문자열로. 12000 → "12", 12500 → "12.5". */
private fun secondsText(ms: Int): String {
    val text = String.format(Locale.US, "%.2f", ms / 1000.0).trimEnd('0')
    return text.removeSuffix(".")
}

private fun sourceLabel(youTubeVideoId: String?, videoUri: Uri?): String = when {
    youTubeVideoId != null -> "유튜브 영상을 인식했습니다 ($youTubeVideoId)"
    videoUri != null -> "기기 영상을 골랐습니다"
    else -> "아직 영상이 없습니다"
}

private sealed interface BuildResult {
    data class Success(val accepted: ValidationResult.Accepted) : BuildResult
    data class Failure(val message: String) : BuildResult
}

/**
 * 입력칸을 전사로 만든다.
 *
 * @param base 분석 결과나 저장된 전사. 있으면 출처·유형·수준·시각 단위·표현을 이어받는다.
 *   저장된 영상을 고칠 때 출처를 바꾸면 계획 ID가 달라져 새 영상이 되어 버린다.
 */
private fun buildTranscript(
    drafts: List<SentenceDraft>,
    youTubeVideoId: String?,
    base: Transcript?,
): BuildResult {
    val rawSentences = drafts.mapIndexedNotNull { index, draft ->
        val text = draft.text.trim().ifBlank { return@mapIndexedNotNull null }
        val start = draft.startMs ?: return@mapIndexedNotNull null
        val end = draft.endMs ?: return@mapIndexedNotNull null
        val origin = draft.origin
        // 문장을 고쳤으면 원래 문장에 딸린 발음·호흡 조각은 맞지 않는다.
        val sameText = origin != null && origin.text == text
        RawSentence(
            index = index,
            text = text,
            translationKo = draft.translationKo.trim(),
            transliterationKo = origin?.transliterationKo?.takeIf { sameText },
            startMs = start,
            endMs = end,
            // 문장에 없는 키워드는 V-5가 걸러 내고, 비면 V-6이 내용어로 채운다.
            keywords = origin?.keywords.orEmpty(),
            breathGroups = if (sameText) {
                origin?.breathGroups.orEmpty().map { RawBreathGroup(it.text, it.startMs, it.endMs) }
            } else {
                emptyList()
            },
        )
    }

    if (rawSentences.isEmpty()) {
        return BuildResult.Failure("문장과 시작·끝 시각을 적어 주세요.")
    }

    val isYouTube = youTubeVideoId != null
    val raw = RawTranscript(
        schemaVersion = 1,
        source = base?.source?.name ?: if (isYouTube) VideoSource.YOUTUBE.name else VideoSource.UPLOAD.name,
        sourceRef = base?.sourceRef ?: youTubeVideoId ?: UUID.randomUUID().toString(),
        language = "en",
        type = base?.type?.name, // 없으면 V-7이 DIALOGUE로 폴백한다
        cefr = base?.cefr?.name, // 없으면 V-7이 B1으로 폴백한다
        // 유튜브는 IFrame Player가 초 단위로만 위치를 알려 준다. SECOND로 두면
        // 재생 시 ±300ms 패딩이 붙어 문장 앞뒤가 잘리지 않는다 (TRANSCRIPTION_SCHEMA §1.1).
        timestampUnit = base?.timestampUnit?.name
            ?: if (isYouTube) TimestampUnit.SECOND.name else TimestampUnit.MILLISECOND.name,
        // 인사말 같은 문장을 지웠으면 발화 구간도 줄어든다 — 반복 횟수 계산의 기준이다.
        speechStartMs = rawSentences.minOf { it.startMs!! },
        speechEndMs = rawSentences.maxOf { it.endMs!! },
        sentences = rawSentences,
        expressions = base?.expressions.orEmpty().map { RawExpression(it.text, it.meaningKo) },
        warnings = base?.warnings.orEmpty().map { it.name },
    )

    return when (val result = TranscriptValidator.validate(raw)) {
        is ValidationResult.Rejected -> BuildResult.Failure("입력을 확인해 주세요. (${result.reason})")
        is ValidationResult.Accepted -> BuildResult.Success(result)
    }
}
