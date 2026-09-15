package com.eng.shadowing.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.eng.shadowing.data.ShadowingRepository
import com.eng.shadowing.ui.entry.ManualEntryScreen
import com.eng.shadowing.ui.home.HomeScreen
import com.eng.shadowing.ui.onboarding.OnboardingScreen
import com.eng.shadowing.ui.session.SessionScreen
import com.eng.shadowing.ui.session.SessionSetupScreen
import java.time.LocalDate

/**
 * 화면 전환. S0는 화면이 다섯이라 navigation-compose를 넣지 않는다 —
 * 의존 하나를 아끼는 것이 아니라, 세션 화면이 뒤로가기로 중간에 끊기지 않게 하는 쪽이 중요하다.
 */
internal sealed interface Screen {
    data object Onboarding : Screen
    data object Home : Screen
    data class ManualEntry(val videoUri: Uri?) : Screen
    data class SessionSetup(val planId: String) : Screen
    data class Session(val planId: String) : Screen
}

@Composable
public fun AppRoot(
    repository: ShadowingRepository,
    sharedVideoUri: Uri?,
    onSharedVideoConsumed: () -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
) {
    val state by repository.state.collectAsState()
    val today = remember { LocalDate.now() }

    var screen: Screen by remember {
        mutableStateOf(
            if (repository.state.value.settings.onboardedAtEpochMs == null) Screen.Onboarding else Screen.Home,
        )
    }

    // 공유로 들어온 영상은 바로 문장 입력 화면으로 보낸다.
    // 컴포지션 도중에 상태를 바꾸면 재구성이 꼬이므로 부수효과로 뺀다.
    LaunchedEffect(sharedVideoUri) {
        if (sharedVideoUri != null) {
            screen = Screen.ManualEntry(sharedVideoUri)
            onSharedVideoConsumed()
        }
    }

    when (val current = screen) {
        Screen.Onboarding -> OnboardingScreen(
            onSelect = { targetSec ->
                repository.updateSettings {
                    it.copy(dailyTargetSec = targetSec, onboardedAtEpochMs = System.currentTimeMillis())
                }
                screen = Screen.Home
            },
        )

        Screen.Home -> HomeScreen(
            state = state,
            today = repository.today(today),
            streak = repository.streak(today),
            onAddVideo = { screen = Screen.ManualEntry(null) },
            onOpenPlan = { planId -> screen = Screen.SessionSetup(planId) },
        )

        is Screen.ManualEntry -> ManualEntryScreen(
            initialVideoUri = current.videoUri,
            onCancel = { screen = Screen.Home },
            onSaved = { plan, localUri ->
                repository.putPlan(plan, localUri)
                screen = Screen.SessionSetup(plan.id.value)
            },
            dailyTargetSec = state.settings.dailyTargetSec,
        )

        is Screen.SessionSetup -> {
            val plan = state.plans[current.planId]
            if (plan == null) {
                LaunchedEffect(current.planId) { screen = Screen.Home }
            } else {
                SessionSetupScreen(
                    plan = plan,
                    dailyTargetSec = state.settings.dailyTargetSec,
                    achievedSec = repository.today(today).achievedSec,
                    onStart = { adjusted ->
                        repository.putPlan(adjusted, repository.localMediaUri(adjusted.id.value))
                        screen = Screen.Session(adjusted.id.value)
                    },
                    onBack = { screen = Screen.Home },
                )
            }
        }

        is Screen.Session -> {
            val plan = state.plans[current.planId]
            val mediaUri = repository.localMediaUri(current.planId)
            if (plan == null || mediaUri == null) {
                LaunchedEffect(current.planId) { screen = Screen.Home }
            } else {
                SessionScreen(
                    plan = plan,
                    mediaUri = Uri.parse(mediaUri),
                    playbackRate = state.settings.playbackRate,
                    showTransliteration = state.settings.showTransliterationKo,
                    onKeepScreenOn = onKeepScreenOn,
                    onFinished = { achievedSec, counts ->
                        repository.addProgress(today, current.planId, achievedSec, counts)
                        repository.applyBudgetAdaptation(today)
                        screen = Screen.Home
                    },
                )
            }
        }
    }
}
