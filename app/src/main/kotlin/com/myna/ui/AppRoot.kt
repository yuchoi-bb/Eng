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
import com.myna.transcribe.TranscribeResult
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
    /** 저장된 영상의 문장을 고친다 — 필요 없는 문장 지우기, 시작·끝 맞추기. */
    data class EditPlan(val planId: String) : Screen
    /** @param resume 저장된 자리에서 이어서 한다. false면 처음부터. */
    data class Session(val planId: String, val options: SessionOptions, val resume: Boolean) : Screen
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
    // 설정의 "새 버전 지금 확인"을 누를 때마다 오른다.
    var updateCheckRequest by remember { mutableStateOf(0) }
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
        // 설치하면 앱이 닫힌다. 연습 중에는 받아만 두고 나간 뒤에 설치한다.
        inSession = screen is Screen.Session,
        checkRequest = updateCheckRequest,
        onChecked = repository::recordUpdateCheck,
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
                    onStart = { adjusted, options, resume ->
                        repository.putPlan(adjusted, repository.localMediaUri(adjusted.id.value))
                        screen = Screen.Session(adjusted.id.value, options, resume)
                    },
                    onBack = { screen = Screen.Home },
                    onEditSentences = { screen = Screen.EditPlan(plan.id.value) },
                )
            }
        }

        is Screen.EditPlan -> {
            val plan = state.plans[current.planId]
            if (plan == null) {
                LaunchedEffect(current.planId) { screen = Screen.Home }
            } else {
                val source = plan.transcript.source
                ManualEntryScreen(
                    initialVideoUri = repository.localMediaUri(current.planId)
                        ?.takeIf { source == VideoSource.UPLOAD }
                        ?.let(Uri::parse),
                    initialYouTubeVideoId = plan.transcript.sourceRef.takeIf { source == VideoSource.YOUTUBE },
                    dailyTargetSec = state.settings.dailyTargetSec,
                    hasApiKey = false,
                    onTranscribe = { TranscribeResult.NoApiKey },
                    onOpenApiKeySettings = {},
                    onCancel = { screen = Screen.SessionSetup(current.planId) },
                    onSaved = { _, _ -> },
                    editing = plan,
                    onEdited = { edited, originOfNew ->
                        repository.replaceSentences(edited, originOfNew)
                        screen = Screen.SessionSetup(current.planId)
                    },
                )
            }
        }

        is Screen.Session -> {
            val plan = state.plans[current.planId]
            // 오프라인이면 들어 본 적 있는 문장만 돈다. 문장 번호는 원래대로 둔다 —
            // 외운 문장 표시와 이어서 하기 자리가 그 번호를 쓴다.
            // 메모 연습은 원래 원본이 없다. 회선이 없어서 원본을 못 듣는 경우만 오프라인 복습이다.
            val offline = !current.options.sourceAudioAvailable &&
                plan?.transcript?.source != VideoSource.NOTE
            val sentenceFilter = if (plan != null && offline) {
                OfflineReadiness.practicableSentenceIndexes(plan, state.clearedSentenceIds)
            } else {
                null
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
            val playable = plan != null && when {
                offline -> sentenceFilter != null
                plan.transcript.source == VideoSource.NOTE -> true
                else -> sessionSource != null
            }

            if (plan == null || !playable) {
                LaunchedEffect(current.planId) { screen = Screen.Home }
            } else {
                // 일부 문장만 도는 오프라인 복습은 자리를 적지 않는다 — 건너뛴 문장이
                // 영영 건너뛰어진다.
                val savesPosition = sentenceFilter == null
                SessionScreen(
                    plan = plan,
                    source = sessionSource,
                    options = current.options,
                    playbackRate = state.settings.playbackRate,
                    showTransliteration = state.settings.showTransliterationKo,
                    dailyAlreadySec = repository.today(today).achievedSec,
                    dailyTargetSec = state.settings.dailyTargetSec,
                    startAt = plan.resumeAt.takeIf { current.resume && savesPosition },
                    sentenceFilter = sentenceFilter,
                    onKeepScreenOn = onKeepScreenOn,
                    onCountCompleted = { event ->
                        repository.recordCount(
                            date = today,
                            planId = current.planId,
                            sentenceIndex = event.sentenceIndex,
                            achievedSec = event.achievedSecDelta,
                            nextPosition = event.nextPosition,
                            savesPosition = savesPosition,
                        )
                    },
                    onMemorizedChanged = { index, memorized ->
                        repository.setMemorized(current.planId, index, memorized)
                    },
                    onSentenceSkipped = { next ->
                        if (savesPosition) repository.saveResumePosition(current.planId, next)
                    },
                    onExit = {
                        repository.applyBudgetAdaptation(today)
                        screen = Screen.Home
                    },
                )
            }
        }

        Screen.ApiKeySettings -> ApiKeyScreen(
            updateStatus = updateStatusText(state.lastUpdateCheckEpochMs, state.lastUpdateOutcome),
            // 다음 화면 복귀를 기다리지 않고 바로 확인한다.
            onCheckUpdate = { updateCheckRequest += 1 },
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

/** 설정 화면에 보여 줄 업데이트 상태 한 줄. */
private fun updateStatusText(lastCheckedEpochMs: Long?, outcome: String?): String {
    val checked = when {
        lastCheckedEpochMs == null || lastCheckedEpochMs == 0L -> null
        else -> {
            val minutes = (System.currentTimeMillis() - lastCheckedEpochMs) / 60_000
            when {
                minutes < 1 -> "방금 확인"
                minutes < 60 -> "${minutes}분 전 확인"
                else -> "${minutes / 60}시간 전 확인"
            }
        }
    }
    val last = listOfNotNull(checked, outcome).joinToString(" · ").ifEmpty { "아직 확인하지 않았습니다" }
    return "앱을 켤 때마다 새 버전을 확인하고, 와이파이면 알아서 받아 설치합니다.\n$last"
}
