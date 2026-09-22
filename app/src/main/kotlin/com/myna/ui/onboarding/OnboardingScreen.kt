package com.myna.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myna.core.model.UserSettings

/**
 * REQUIREMENTS §4.7 — 온보딩에서는 **목표 시간만** 고른다.
 * 개인화 판단 없이 시작하고, 3일차부터 적응 로직이 조정한다.
 */
@Composable
internal fun OnboardingScreen(onSelect: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("하루에 얼마나 말할까요?", style = MaterialTheme.typography.headlineSmall)
        Text(
            "나중에 바꿀 수 있어요. 실제로 채운 양에 맞춰 3일차부터 자동으로 조정됩니다.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )

        UserSettings.ONBOARDING_TARGETS_SEC.forEach { targetSec ->
            Button(
                onClick = { onSelect(targetSec) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Text("하루 ${targetSec / 60}분")
            }
        }
    }
}
