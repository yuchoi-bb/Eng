package com.myna.ui.script

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.myna.core.model.Sentence
import com.myna.core.model.VideoPlan

/**
 * 영상의 전체 스크립트. 문장마다 영어·한글 뜻(·발음)을 보여 준다.
 *
 * 연습 화면은 한 번에 한 문장만 보여 주므로 영상 전체의 흐름을 알 수 없다. 시작 전에 훑어보거나,
 * 연습 뒤에 복습할 때 쓴다. 외운 문장과 이어서 할 문장을 표시해 어디까지 왔는지 보이게 한다.
 */
@Composable
internal fun ScriptScreen(
    plan: VideoPlan,
    showTransliteration: Boolean,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val resumeIndex = plan.resumeAt?.sentenceIndex
    val listState = rememberLazyListState()

    // 이어서 할 문장이 화면 밖이면 그 근처로 내려가 둔다.
    LaunchedEffect(plan.id.value) {
        if (resumeIndex != null && resumeIndex > 2) listState.scrollToItem(resumeIndex - 1)
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

        LazyColumn(Modifier.weight(1f).padding(top = 8.dp), state = listState) {
            itemsIndexed(plan.sentences, key = { _, sentence -> sentence.index }) { position, sentence ->
                SentenceRow(
                    number = position + 1,
                    sentence = sentence,
                    memorized = position in plan.memorizedSentences,
                    isResume = position == resumeIndex,
                    showTransliteration = showTransliteration,
                )
                HorizontalDivider()
            }
        }

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("돌아가기") }
    }
}

@Composable
private fun SentenceRow(
    number: Int,
    sentence: Sentence,
    memorized: Boolean,
    isResume: Boolean,
    showTransliteration: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$number · ${timeText(sentence.startMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (memorized) Badge("외움")
            if (isResume) Badge("이어서 할 문장")
        }
        Text(
            sentence.text,
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
            Text(sentence.translationKo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        }
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
