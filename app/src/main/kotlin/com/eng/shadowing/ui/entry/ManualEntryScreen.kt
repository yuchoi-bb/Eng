package com.eng.shadowing.ui.entry

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.eng.shadowing.core.model.TimestampUnit
import com.eng.shadowing.core.model.VideoPlan
import com.eng.shadowing.core.model.VideoPlanId
import com.eng.shadowing.core.model.VideoSource
import com.eng.shadowing.core.session.RepsCalculator
import com.eng.shadowing.core.source.YouTubeUrl
import com.eng.shadowing.core.transcript.RawSentence
import com.eng.shadowing.core.transcript.RawTranscript
import com.eng.shadowing.core.transcript.TranscriptValidator
import com.eng.shadowing.core.transcript.ValidationResult
import java.util.UUID

private class SentenceDraft {
    var text by mutableStateOf("")
    var translationKo by mutableStateOf("")
    var startSec by mutableStateOf("")
    var endSec by mutableStateOf("")
}

/**
 * 영상 추가 + 문장 직접 입력. TRANSCRIPTION_SCHEMA §4.3.
 *
 * 영상은 두 경로로 들어온다.
 * - **유튜브 링크** (F-1) — 쇼츠 URL을 붙여 넣거나 유튜브 앱에서 공유한다
 * - **기기 영상** (F-2) — Photo Picker로 고른다
 *
 * 문장 입력은 S0에서 전사 API가 없기 때문만이 아니다. 명세는 이 경로를
 * **"선택이 아니라 필수"**로 규정했다 — S1에서 전사가 붙어도 추출 실패 영상은 여기로 온다.
 *
 * 사람이 적은 값도 [TranscriptValidator]를 그대로 통과시킨다. 검증을 건너뛰면 시각이
 * 뒤집힌 문장이나 문장에 없는 키워드가 그대로 저장된다.
 */
@Composable
internal fun ManualEntryScreen(
    initialVideoUri: Uri?,
    initialYouTubeVideoId: String?,
    dailyTargetSec: Int,
    onCancel: () -> Unit,
    onSaved: (VideoPlan, String?) -> Unit,
) {
    var videoUri by remember { mutableStateOf(initialVideoUri) }
    var youTubeVideoId by remember { mutableStateOf(initialYouTubeVideoId) }
    var linkInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val drafts = remember { mutableStateListOf(SentenceDraft()) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            videoUri = uri
            youTubeVideoId = null // 두 소스를 동시에 둘 수 없다
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("영상 추가", style = MaterialTheme.typography.headlineSmall)

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = linkInput,
            onValueChange = { input ->
                linkInput = input
                // 붙여 넣는 즉시 인식한다. 유튜브 앱은 제목과 문구를 함께 보내므로
                // 사용자가 URL만 골라 내게 하지 않는다.
                YouTubeUrl.extractVideoId(input)?.let {
                    youTubeVideoId = it
                    videoUri = null
                }
            },
            label = { Text("유튜브 쇼츠 링크") },
            placeholder = { Text("https://youtube.com/shorts/...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("또는 기기에서 영상 고르기")
        }

        Text(
            sourceLabel(youTubeVideoId, videoUri),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "영상에서 들리는 문장과 시각을 적어 주세요. 한 문장씩 나눠 적을수록 반복이 쉬워집니다.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(drafts) { index, draft ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("문장 ${index + 1}", style = MaterialTheme.typography.labelLarge)
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
                            OutlinedTextField(
                                value = draft.startSec,
                                onValueChange = { draft.startSec = it },
                                label = { Text("시작(초)") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Next,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = draft.endSec,
                                onValueChange = { draft.endSec = it },
                                label = { Text("끝(초)") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Done,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        TextButton(onClick = { drafts.add(SentenceDraft()) }) {
            Text("+ 문장 추가")
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
                    val youTubeId = youTubeVideoId
                    val localUri = videoUri
                    if (youTubeId == null && localUri == null) {
                        error = "유튜브 링크를 붙여 넣거나 기기에서 영상을 골라 주세요."
                        return@Button
                    }
                    when (val result = buildPlan(drafts, dailyTargetSec, youTubeId)) {
                        is BuildResult.Failure -> error = result.message
                        // 유튜브는 미디어를 저장하지 않는다 — 앱은 URL과 텍스트만 보관한다(§F-1).
                        is BuildResult.Success -> onSaved(result.plan, localUri?.toString())
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("저장하고 시작")
            }
        }
    }
}

private fun sourceLabel(youTubeVideoId: String?, videoUri: Uri?): String = when {
    youTubeVideoId != null -> "유튜브 영상을 인식했습니다 ($youTubeVideoId)"
    videoUri != null -> "기기 영상을 골랐습니다"
    else -> "아직 영상이 없습니다"
}

private sealed interface BuildResult {
    data class Success(val plan: VideoPlan) : BuildResult
    data class Failure(val message: String) : BuildResult
}

private fun buildPlan(
    drafts: List<SentenceDraft>,
    dailyTargetSec: Int,
    youTubeVideoId: String?,
): BuildResult {
    val rawSentences = drafts.mapIndexedNotNull { index, draft ->
        val text = draft.text.trim().ifBlank { return@mapIndexedNotNull null }
        val start = draft.startSec.trim().toDoubleOrNull() ?: return@mapIndexedNotNull null
        val end = draft.endSec.trim().toDoubleOrNull() ?: return@mapIndexedNotNull null
        RawSentence(
            index = index,
            text = text,
            translationKo = draft.translationKo.trim(),
            startMs = (start * 1000).toInt(),
            endMs = (end * 1000).toInt(),
            // 수동 입력에는 키워드가 없다. V-6이 내용어로 채운다.
            keywords = emptyList(),
        )
    }

    if (rawSentences.isEmpty()) {
        return BuildResult.Failure("문장과 시작·끝 시각을 적어 주세요.")
    }

    val isYouTube = youTubeVideoId != null
    val raw = RawTranscript(
        schemaVersion = 1,
        source = if (isYouTube) VideoSource.YOUTUBE.name else VideoSource.UPLOAD.name,
        sourceRef = youTubeVideoId ?: UUID.randomUUID().toString(),
        language = "en",
        type = null, // V-7이 DIALOGUE로 폴백한다
        cefr = null, // V-7이 B1으로 폴백한다
        // 유튜브는 IFrame Player가 초 단위로만 위치를 알려 준다. SECOND로 두면
        // 재생 시 ±300ms 패딩이 붙어 문장 앞뒤가 잘리지 않는다 (TRANSCRIPTION_SCHEMA §1.1).
        timestampUnit = if (isYouTube) TimestampUnit.SECOND.name else TimestampUnit.MILLISECOND.name,
        speechStartMs = rawSentences.minOf { it.startMs!! },
        speechEndMs = rawSentences.maxOf { it.endMs!! },
        sentences = rawSentences,
    )

    return when (val result = TranscriptValidator.validate(raw)) {
        is ValidationResult.Rejected -> BuildResult.Failure("입력을 확인해 주세요. (${result.reason})")
        is ValidationResult.Accepted -> {
            val transcript = result.transcript
            val suggested = RepsCalculator.suggestedReps(dailyTargetSec, transcript)
            BuildResult.Success(
                VideoPlan(
                    id = VideoPlanId.of(transcript.source, transcript.sourceRef),
                    transcript = transcript,
                    suggestedReps = suggested,
                    targetReps = suggested,
                ),
            )
        }
    }
}
