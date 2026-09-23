package com.myna.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.myna.core.model.VideoSource
import com.myna.core.notes.FieldNote
import com.myna.core.offline.OfflineReadiness
import com.myna.core.session.SessionOptions
import com.myna.data.ApiKeyStore
import com.myna.data.NetworkMonitor
import com.myna.data.ShadowingRepository
import com.myna.transcribe.GeminiTranscriber
import com.myna.ui.settings.ApiKeyScreen
import com.myna.ui.entry.ManualEntryScreen
import com.myna.ui.home.HomeScreen
import com.myna.ui.onboarding.OnboardingScreen
import com.myna.ui.session.SessionScreen
import com.myna.ui.session.SessionSetupScreen
import com.myna.ui.session.SessionSource
import com.myna.ui.notes.CaptureNoteScreen
import com.myna.ui.notes.NoteListScreen
import com.myna.ui.notes.ResolveNoteScreen
import com.myna.ui.update.UpdateGate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState as collectFlowAsState
import java.time.LocalDate

/**
 * 화면 전환. S0는 화면이 다섯이라 navigation-compose를 넣지 않는다 —
 * 의존 하나를 아끼는 것이 아니라, 세션 화면이 뒤로가기로 중간에 끊기지 않게 하는 쪽이 중요하다.
 */
internal sealed interface Screen {
    data object Onboarding : Screen
    data object Home : Screen
    data class ManualEntry(val videoUri: Uri?, val youTubeVideoId: String? = null) : Screen
    data class SessionSetup(val planId: String) : Screen
    data class Session(val planId: String, val options: SessionOptions) : Screen
    data object CaptureNote : Screen
    data object NoteList : Screen
    data class ResolveNote(val noteId: String) : Screen
    data object ApiKeySettings : Screen
}

@Composable
internal fun AppRoot(
    repository: ShadowingRepository,
    apiKeys: ApiKeyStore,
    sharedVideoUri: Uri?,
    sharedYouTubeVideoId: String?,
    onSharedVideoConsumed: () -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
) {
    val state by repository.state.collectAsState()
    val today = remember { LocalDate.now() }
    val context = LocalContext.current
    val networkMonitor = remember { NetworkMonitor(context) }
    val transcriber = remember(apiKeys) { GeminiTranscriber(apiKeys) }
    // 키가 바뀌면 화면이 다시 그려져야 한다. 설정 저장 시 이 값을 올린다.
    var keyRevision by remember { mutableStateOf(0) }
    val online by networkMonitor.observe().collectFlowAsState(initial = networkMonitor.isOnline())

    var screen: Screen by remember {
        mutableStateOf(
            if (repository.state.value.settings.onboardedAtEpochMs == null) Screen.Onboarding else Screen.Home,
        )
    }

    // 공유로 들어온 영상이나 유튜브 링크는 바로 문장 입력 화면으로 보낸다.
    // 컴포지션 도중에 상태를 바꾸면 재구성이 꼬이므로 부수효과로 뺀다.
    LaunchedEffect(sharedVideoUri, sharedYouTubeVideoId) {
        if (sharedVideoUri != null || sharedYouTubeVideoId != null) {
            screen = Screen.ManualEntry(sharedVideoUri, sharedYouTubeVideoId)
            onSharedVideoConsumed()
        }
    }

    // 사이드로드 배포라 자동 업데이트가 없다. 새 릴리즈가 나오면 앱이 직접 묻는다.
    UpdateGate(
        lastCheckedEpochMs = state.lastUpdateCheckEpochMs,
        skippedVersion = state.skippedUpdateVersion,
        onChecked = repository::recordUpdateCheck,
        onSkipVersion = repository::skipUpdateVersion,
    )

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
            online = online,
            noteCount = state.fieldNotes.count { !it.isResolved },
            onAddVideo = { screen = Screen.ManualEntry(null) },
            onOpenPlan = { planId -> screen = Screen.SessionSetup(planId) },
            onCaptureNote = { screen = Screen.CaptureNote },
            onOpenNotes = { screen = Screen.NoteList },
            onOpenSettings = { screen = Screen.ApiKeySettings },
        )

        is Screen.ManualEntry -> ManualEntryScreen(
            initialVideoUri = current.videoUri,
            initialYouTubeVideoId = current.youTubeVideoId,
            hasApiKey = remember(keyRevision) { apiKeys.hasKey },
            onTranscribe = { videoId -> transcriber.transcribeYouTube(videoId) },
            onOpenApiKeySettings = { screen = Screen.ApiKeySettings },
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
                    online = online,
                    offlineStatus = OfflineReadiness.statusFor(plan, state.clearedSentenceIds),
                    // 유튜브는 임베드 재생이라 회선이 필요하다. 메모 연습은 원본 자체가 없다.
                    needsSourceAudio = plan.transcript.source == VideoSource.YOUTUBE,
                    onStart = { adjusted, options ->
                        repository.putPlan(adjusted, repository.localMediaUri(adjusted.id.value))
                        screen = Screen.Session(adjusted.id.value, options)
                    },
                    onBack = { screen = Screen.Home },
                )
            }
        }

        is Screen.Session -> {
            val stored = state.plans[current.planId]
            // 오프라인이면 들어 본 적 있는 문장만 남긴 사본으로 돈다.
            val plan = when {
                stored == null -> null
                current.options.sourceAudioAvailable -> stored
                else -> OfflineReadiness.practicablePlan(stored, state.clearedSentenceIds)
            }
            // 유튜브는 sourceRef가 곧 영상 ID다. 업로드는 이 기기에 원본이 있어야 한다(§10.1).
            val sessionSource = when {
                plan == null || !current.options.sourceAudioAvailable -> null
                plan.transcript.source == VideoSource.YOUTUBE ->
                    SessionSource.YouTube(plan.transcript.sourceRef)
                plan.transcript.source == VideoSource.NOTE -> null
                else -> repository.localMediaUri(current.planId)
                    ?.let { SessionSource.Upload(Uri.parse(it)) }
            }
            val playable = plan != null &&
                (!current.options.sourceAudioAvailable || sessionSource != null ||
                    plan.transcript.source == VideoSource.NOTE)

            if (plan == null || !playable) {
                LaunchedEffect(current.planId) { screen = Screen.Home }
            } else {
                SessionScreen(
                    plan = plan,
                    source = sessionSource,
                    options = current.options,
                    playbackRate = state.settings.playbackRate,
                    showTransliteration = state.settings.showTransliterationKo,
                    onKeepScreenOn = onKeepScreenOn,
                    onSentenceCleared = { index ->
                        repository.markSentenceCleared(current.planId, index)
                    },
                    onFinished = { achievedSec, counts ->
                        repository.addProgress(today, current.planId, achievedSec, counts)
                        repository.applyBudgetAdaptation(today)
                        screen = Screen.Home
                    },
                )
            }
        }

        Screen.ApiKeySettings -> ApiKeyScreen(
            currentKey = remember(keyRevision) { apiKeys.geminiKey },
            currentModel = remember(keyRevision) { apiKeys.modelOverride },
            resolvedModel = remember(keyRevision) { apiKeys.resolvedModel },
            unsupportedModels = remember(keyRevision) { apiKeys.unsupportedModels },
            onSave = { key, model ->
                // 키가 바뀌면 모델 판단을 처음부터 다시 한다. 키마다 쓸 수 있는 모델이 다르다.
                if (key != apiKeys.geminiKey) apiKeys.forgetModelDiscovery()
                apiKeys.geminiKey = key
                apiKeys.modelOverride = model
                keyRevision += 1
                screen = Screen.Home
            },
            onForgetModels = {
                apiKeys.forgetModelDiscovery()
                keyRevision += 1
            },
            onBack = { screen = Screen.Home },
        )

        Screen.CaptureNote -> CaptureNoteScreen(
            onCancel = { screen = Screen.Home },
            onSave = { memo, situation ->
                repository.addFieldNote(memo, situation, System.currentTimeMillis())
                screen = Screen.NoteList
            },
        )

        Screen.NoteList -> NoteListScreen(
            notes = state.fieldNotes,
            onBack = { screen = Screen.Home },
            onOpen = { note -> screen = Screen.ResolveNote(note.id) },
            onPractice = { planId -> screen = Screen.SessionSetup(planId) },
        )

        is Screen.ResolveNote -> {
            val note: FieldNote? = state.fieldNotes.firstOrNull { it.id == current.noteId }
            if (note == null) {
                LaunchedEffect(current.noteId) { screen = Screen.NoteList }
            } else {
                ResolveNoteScreen(
                    note = note,
                    onBack = { screen = Screen.NoteList },
                    onResolve = { english ->
                        val plan = repository.resolveFieldNote(note.id, english, System.currentTimeMillis())
                        screen = if (plan != null) Screen.SessionSetup(plan.id.value) else Screen.NoteList
                    },
                    onDelete = {
                        repository.deleteFieldNote(note.id)
                        screen = Screen.NoteList
                    },
                )
            }
        }
    }
}
