package com.myna.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Gemini API 키 입력.
 *
 * 이 키가 있어야 유튜브 링크만 넣고 문장이 자동으로 채워진다. 없으면 직접 적어야 한다 —
 * 그 경로는 그대로 남는다(TRANSCRIPTION_SCHEMA §4.3이 수동 입력을 필수로 규정했다).
 */
@Composable
internal fun ApiKeyScreen(
    currentKey: String?,
    currentModel: String?,
    resolvedModel: String?,
    unsupportedModels: Set<String>,
    onSave: (key: String?, model: String?) -> Unit,
    onForgetModels: () -> Unit,
    onBack: () -> Unit,
) {
    var key by remember { mutableStateOf(currentKey.orEmpty()) }
    var model by remember { mutableStateOf(currentModel.orEmpty()) }
    var reveal by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("자동 문장 채우기", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Gemini API 키를 넣으면 유튜브 링크만으로 문장과 뜻이 자동으로 채워집니다.\n" +
                "aistudio.google.com 에서 무료로 발급받을 수 있습니다.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )

        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API 키") },
            singleLine = true,
            visualTransformation = if (reveal) {
                androidx.compose.ui.text.input.VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { reveal = !reveal }) {
            Text(if (reveal) "가리기" else "보기")
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("모델 (비워 두면 자동)") },
            placeholder = { Text(resolvedModel ?: "앱이 목록에서 고릅니다") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "모델 이름은 자주 바뀝니다. 비워 두면 앱이 목록에서 후보를 골라 차례로 시도하고, " +
                "영상을 받아 준 모델을 기억합니다.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )

        resolvedModel?.let {
            Text(
                "지난번에 통한 모델: $it",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (unsupportedModels.isNotEmpty()) {
            // 어떤 모델이 영상을 받는지는 목록으로 알 수 없어 눌러 봐야 안다.
            // 무엇이 거절했는지 보여 주면 직접 지정할 때 참고가 된다.
            Text(
                "영상을 거절한 모델: ${unsupportedModels.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onForgetModels) { Text("모델 판단 초기화") }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "키는 이 기기의 앱 전용 저장소에만 남습니다. 서버로 보내지 않고, 전사할 때 " +
                "Google에 직접 요청합니다. 언제든 지우거나 Google에서 폐기할 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("뒤로") }
            Button(
                onClick = { onSave(key.ifBlank { null }, model.ifBlank { null }) },
                modifier = Modifier.weight(1f),
            ) {
                Text("저장")
            }
        }
    }
}
