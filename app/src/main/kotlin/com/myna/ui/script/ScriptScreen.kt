package com.myna.ui.script

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myna.core.model.Sentence
import com.myna.core.model.TimestampUnit
import com.myna.core.model.VideoPlan
import com.myna.core.playback.PlaybackSegment
import com.myna.core.playback.SegmentPadding
import com.myna.core.playback.WordTimeline
import com.myna.media.SourcePlayback
import com.myna.ui.entry.SegmentPreviewPlayer

/** 🎧를 누르고 있는 동안의 상태. */
private enum class HoldMode { IDLE, PLAYING, REWINDING }

/**
 * 영상의 전체 스크립트. 문장마다 영어·한글 뜻(·발음)을 보여 준다.
 *
 * 문장 옆 🎧를 **꾹 누르고 있으면** 그 문장을 들려주며 말하는 위치까지 글자를 칠한다. **떼면**
 * 소리가 멈추고 칠한 글자가 2배 속도로 되감기듯 빠진다. 빠지는 중에 **다시 누르면** 그 지점부터
 * 다시 들린다. 안 들린 곳만 골라 되풀이해 듣기 위한 것이다.
 *
 * 글자를 소리에 맞추는 기준은 단어별 시각이다(TRANSCRIPTION_SCHEMA §4.6). 아직 없는 문장은
 * 글자 수에 비례해 칠한다.
 */
@Composable
internal fun ScriptScreen(
    plan: VideoPlan,
    showTransliteration: Boolean,
    youTubeVideoId: String?,
    videoUri: Uri?,
    playbackRate: Float,
    aligning: Boolean,
    alignStatus: String?,
    canAlign: Boolean,
    onAlign: () -> Unit,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val resumeIndex = plan.resumeAt?.sentenceIndex
    val listState = rememberLazyListState()
    val hasSource = youTubeVideoId != null || videoUri != null
    val missingWords = plan.sentences.count { it.words.isEmpty() }

    // 이어서 할 문장이 화면 밖이면 그 근처로 내려가 둔다.
    LaunchedEffect(plan.id.value) {
        if (resumeIndex != null && resumeIndex > 2) listState.scrollToItem(resumeIndex - 1)
    }

    var playback by remember { mutableStateOf<SourcePlayback?>(null) }
    var active by remember { mutableStateOf<Int?>(null) }
    var mode by remember { mutableStateOf(HoldMode.IDLE) }
    var head by remember { mutableFloatStateOf(0f) }
    var reachedEnd by remember { mutableStateOf(false) }
    var playerMessage by remember { mutableStateOf<String?>(null) }

    val padding = if (plan.transcript.timestampUnit == TimestampUnit.SECOND) SegmentPadding.PADDING_MS else 0

    fun press(position: Int, sentence: Sentence) {
        val player = playback ?: return
        val (spanStart, spanEnd) = WordTimeline.span(sentence)
        if (active != position) {
            player.pause()
            active = position
            head = spanStart.toFloat()
        }
        // 다 들은 뒤 다시 누르면 처음부터.
        if (head >= spanEnd - END_SLACK_MS) head = spanStart.toFloat()
        reachedEnd = false
        playerMessage = null
        mode = HoldMode.PLAYING
        player.playFrom(PlaybackSegment(head.toInt(), spanEnd + padding), playbackRate) { reachedEnd = true }
    }

    fun release() {
        if (mode != HoldMode.PLAYING) return
        playback?.pause()
        mode = HoldMode.REWINDING
    }

    // 누르는 동안은 재생 위치를 따라가고, 뗀 뒤에는 2배 속도로 되감는다.
    LaunchedEffect(mode, active) {
        val index = active ?: return@LaunchedEffect
        val sentence = plan.sentences.getOrNull(index) ?: return@LaunchedEffect
        val (spanStart, spanEnd) = WordTimeline.span(sentence)
        var last = withFrameNanos { it }
        var pausedAtEnd = false
        while (true) {
            val now = withFrameNanos { it }
            val elapsedMs = (now - last) / 1_000_000f
            last = now
            when (mode) {
                HoldMode.PLAYING -> {
                    val position = playback?.currentPositionMs()?.toFloat()
                    // 위치는 앞으로만 간다. 건너뛴 직후 예전 위치가 늦게 와도 글자가 뒤로 빠지지 않게.
                    if (position != null) head = maxOf(head, position.coerceAtMost(spanEnd.toFloat()))
                    if ((head >= spanEnd || reachedEnd) && !pausedAtEnd) {
                        head = spanEnd.toFloat()
                        playback?.pause()
                        pausedAtEnd = true
                    }
                }
                HoldMode.REWINDING -> {
                    head -= elapsedMs * REWIND_SPEED
                    if (head <= spanStart) {
                        head = spanStart.toFloat()
                        mode = HoldMode.IDLE
                        active = null
                        return@LaunchedEffect
                    }
                }
                HoldMode.IDLE -> return@LaunchedEffect
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("전체 스크립트", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(scriptText(plan.sentences)))
                Toast.makeText(context, "스크립트를 복사했습니다", Toast.LENGTH_SHORT).show()
            }) { Text("복사") }
        }
        Text(
            "문장 ${plan.sentences.size}개 · 외운 문장 ${plan.memorizedCount}개",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 유튜브는 플레이어를 숨기고 소리만 낼 수 없다. 작게라도 보이게 둔다.
        if (hasSource) {
            Box(Modifier.fillMaxWidth().height(PLAYER_HEIGHT).padding(top = 6.dp), contentAlignment = Alignment.Center) {
                SegmentPreviewPlayer(
                    youTubeVideoId = youTubeVideoId,
                    videoUri = videoUri,
                    onPlayback = { playback = it },
                    onRefused = { reason ->
                        mode = HoldMode.IDLE
                        active = null
                        playerMessage = reason
                    },
                    modifier = Modifier.fillMaxHeight().aspectRatio(9f / 16f, matchHeightConstraintsFirst = true),
                )
            }
        }

        AlignmentNotice(
            hasSource = hasSource,
            missingWords = missingWords,
            aligning = aligning,
            status = playerMessage ?: alignStatus,
            canAlign = canAlign,
            onAlign = onAlign,
        )

        LazyColumn(Modifier.weight(1f).padding(top = 4.dp), state = listState) {
            itemsIndexed(plan.sentences, key = { _, sentence -> sentence.index }) { position, sentence ->
                SentenceRow(
                    number = position + 1,
                    sentence = sentence,
                    memorized = position in plan.memorizedSentences,
                    isResume = position == resumeIndex,
                    showTransliteration = showTransliteration,
                    holdable = hasSource && playback != null,
                    holding = active == position && mode == HoldMode.PLAYING,
                    filledChars = if (active == position) {
                        WordTimeline.filledChars(WordTimeline.build(sentence), head)
                    } else {
                        null
                    },
                    onPress = { press(position, sentence) },
                    onRelease = ::release,
                )
                HorizontalDivider()
            }
        }

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("돌아가기") }
    }
}

@Composable
private fun AlignmentNotice(
    hasSource: Boolean,
    missingWords: Int,
    aligning: Boolean,
    status: String?,
    canAlign: Boolean,
    onAlign: () -> Unit,
) {
    if (!hasSource) return
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                aligning -> status ?: "단어 시각을 맞추는 중…"
                status != null -> status
                missingWords > 0 -> "단어 시각이 없는 문장 ${missingWords}개는 글자 수로 맞춰 칠합니다."
                else -> "🎧를 꾹 누르면 들으면서 글자가 칠해지고, 떼면 2배로 되감깁니다."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (missingWords > 0 && canAlign && !aligning) {
            TextButton(onClick = onAlign) { Text("단어 시각 받기") }
        }
    }
}

@Composable
private fun SentenceRow(
    number: Int,
    sentence: Sentence,
    memorized: Boolean,
    isResume: Boolean,
    showTransliteration: Boolean,
    holdable: Boolean,
    holding: Boolean,
    filledChars: Float?,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$number · ${timeText(sentence.startMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (memorized) Badge("외움")
                if (isResume) Badge("이어서 할 문장")
            }
            val baseColor = MaterialTheme.colorScheme.onSurface
            Text(
                if (filledChars == null) {
                    AnnotatedString(sentence.text)
                } else {
                    karaoke(
                        text = sentence.text,
                        filled = filledChars,
                        base = baseColor,
                        from = MaterialTheme.colorScheme.primary,
                        to = MaterialTheme.colorScheme.tertiary,
                    )
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isResume) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(top = 2.dp),
            )
            val transliteration = sentence.transliterationKo
            if (showTransliteration && transliteration != null) {
                Text(
                    transliteration,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sentence.translationKo.isNotBlank()) {
                Text(
                    sentence.translationKo,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (holdable) {
            Spacer(Modifier.width(8.dp))
            HoldButton(holding = holding, onPress = onPress, onRelease = onRelease)
        }
    }
}

/** 누르고 있는 동안만 재생한다. 목록을 스크롤하다 손이 닿아도 스크롤로 넘어가면 놓은 것으로 친다. */
@Composable
private fun HoldButton(holding: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .background(
                if (holding) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                CircleShape,
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("🎧", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Badge(label: String) {
    Spacer(Modifier.width(6.dp))
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * 말한 곳까지 글자를 칠한다. 칠한 글자는 문장 앞에서 뒤로 갈수록 [from]에서 [to]로 색이 변하고,
 * 지금 말하는 경계는 몇 글자에 걸쳐 원래 색으로 번진다.
 */
private fun karaoke(text: String, filled: Float, base: Color, from: Color, to: Color): AnnotatedString =
    buildAnnotatedString {
        append(text)
        val last = (text.length - 1).coerceAtLeast(1)
        for (i in text.indices) {
            val amount = ((filled - i) / FADE_CHARS).coerceIn(0f, 1f)
            if (amount <= 0f) break
            val gradient = lerp(from, to, i.toFloat() / last)
            addStyle(SpanStyle(color = lerp(base, gradient, amount)), i, i + 1)
        }
    }

/** 0:07 — 영상 속 어디쯤인지. */
private fun timeText(ms: Int): String {
    val totalSec = ms / 1000
    return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
}

/** 메모장이나 메신저에 붙여 넣을 수 있게 영어와 뜻을 한 줄씩. */
private fun scriptText(sentences: List<Sentence>): String =
    sentences.joinToString("\n\n") { sentence ->
        listOf(sentence.text, sentence.translationKo).filter { it.isNotBlank() }.joinToString("\n")
    }

/** 뗀 뒤 되감는 속도. 들은 만큼 되감는 데 똑같이 오래 걸리면 답답하다. */
private const val REWIND_SPEED = 2f

/** 칠하는 경계가 번지는 글자 수. */
private const val FADE_CHARS = 2.5f

/** 끝에서 이만큼 안쪽이면 다 들은 것으로 본다. */
private const val END_SLACK_MS = 50

private val PLAYER_HEIGHT = 170.dp
