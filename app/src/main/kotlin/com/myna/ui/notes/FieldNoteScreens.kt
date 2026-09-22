package com.myna.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myna.core.notes.FieldNote
import com.myna.core.notes.NoteSituation

/**
 * 막힌 순간 기록.
 *
 * 현장에서 30초 안에 끝나야 한다. 상황 태그 하나와 한국어 한 줄이면 저장된다 —
 * 지금 영어를 떠올릴 수 있었다면 막히지도 않았을 테니 영어는 묻지 않는다.
 */
@Composable
internal fun CaptureNoteScreen(
    onCancel: () -> Unit,
    onSave: (memo: String, situation: NoteSituation) -> Unit,
) {
    var memo by remember { mutableStateOf("") }
    var situation by remember { mutableStateOf(NoteSituation.OTHER) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("막힌 순간", style = MaterialTheme.typography.headlineSmall)
        Text(
            "지금 하려던 말을 한국어로 적어 두세요. 영어는 나중에 채웁니다.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )

        OutlinedTextField(
            value = memo,
            onValueChange = { memo = it },
            label = { Text("무슨 말을 하려고 했나요?") },
            placeholder = { Text("택시에서 목적지 바꿔 달라고 못 함") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        Text("어디서였나요?", style = MaterialTheme.typography.titleSmall)
        SituationChips(selected = situation, onSelect = { situation = it })

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("취소") }
            Button(
                onClick = { onSave(memo, situation) },
                enabled = memo.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("저장")
            }
        }
    }
}

/** 기록한 메모 목록. 채워지지 않은 것이 먼저 보인다. */
@Composable
internal fun NoteListScreen(
    notes: List<FieldNote>,
    onBack: () -> Unit,
    onOpen: (FieldNote) -> Unit,
    onPractice: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("막힌 순간들", style = MaterialTheme.typography.headlineSmall)

        if (notes.isEmpty()) {
            Text(
                "아직 없습니다. 말하려다 막혔을 때 바로 적어 두면 가장 좋은 연습거리가 됩니다.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            // 아직 영어를 못 채운 것이 위로 온다 — 그게 지금 할 일이다.
            val sorted = notes.sortedBy { it.isResolved }
            LazyColumn(Modifier.weight(1f).padding(top = 12.dp)) {
                items(sorted, key = { it.id }) { note ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onOpen(note) },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(situationLabel(note.situation), style = MaterialTheme.typography.labelSmall)
                            Text(note.koreanMemo, style = MaterialTheme.typography.bodyLarge)
                            val english = note.englishText
                            if (english != null) {
                                Text(
                                    english,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                                note.practicePlanId?.let { planId ->
                                    TextButton(onClick = { onPractice(planId) }) { Text("연습하기") }
                                }
                            } else {
                                Text(
                                    "영어 문장을 채워 주세요",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("뒤로") }
    }
}

/** 메모에 영어 문장을 채운다. 채우는 순간 연습 대상이 된다. */
@Composable
internal fun ResolveNoteScreen(
    note: FieldNote,
    onBack: () -> Unit,
    onResolve: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var english by remember(note.id) { mutableStateOf(note.englishText.orEmpty()) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("영어로 어떻게 말할까", style = MaterialTheme.typography.headlineSmall)

        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(situationLabel(note.situation), style = MaterialTheme.typography.labelSmall)
                Text(note.koreanMemo, style = MaterialTheme.typography.bodyLarge)
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = english,
            onValueChange = { english = it },
            label = { Text("영어 문장") },
            placeholder = { Text("Could you change the destination?") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "짧고 실제로 쓸 말로 적으세요. 길게 적으면 입에 붙지 않습니다.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.weight(1f))
        Button(
            onClick = { onResolve(english) },
            enabled = english.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("저장하고 연습 시작")
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("뒤로") }
            TextButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("삭제") }
        }
    }
}

@Composable
private fun SituationChips(selected: NoteSituation, onSelect: (NoteSituation) -> Unit) {
    // FlowRow는 실험 API라 옵트인이 필요하다. 칩이 아홉 개뿐이니 가로 스크롤로 충분하다.
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        NoteSituation.entries.forEach { situation ->
            FilterChip(
                selected = situation == selected,
                onClick = { onSelect(situation) },
                label = { Text(situationLabel(situation)) },
            )
        }
    }
}

internal fun situationLabel(situation: NoteSituation): String = when (situation) {
    NoteSituation.AIRPORT -> "공항"
    NoteSituation.HOTEL -> "호텔"
    NoteSituation.RESTAURANT -> "식당"
    NoteSituation.TAXI -> "택시"
    NoteSituation.MEETING -> "회의"
    NoteSituation.SMALL_TALK -> "스몰토크"
    NoteSituation.SHOPPING -> "쇼핑"
    NoteSituation.TROUBLE -> "문제 생김"
    NoteSituation.OTHER -> "기타"
}
