package com.myna.ui.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.myna.BuildConfig
import com.myna.core.update.UpdatePolicy
import com.myna.update.ApkInstaller
import com.myna.update.AvailableUpdate
import com.myna.update.CheckResult
import com.myna.update.DownloadState
import com.myna.update.InstallEvents
import com.myna.update.InstallOutcome
import com.myna.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 새 버전 하나를 받아서 설치하기까지의 단계. */
private sealed interface Phase {
    data object Idle : Phase

    /** 데이터 요금이 붙는 회선이라 받기 전에 묻는다. */
    data object AskMetered : Phase
    data class Downloading(val percent: Int?) : Phase

    /** 다 받았다. 연습 중이면 끝날 때까지 기다렸다가 설치한다. */
    data class Downloaded(val file: File) : Phase

    /** "이 앱의 설치 허용"을 켜야 한다. 켜고 돌아오면 이어서 설치한다. */
    data class NeedsPermission(val file: File, val settingsIntent: Intent) : Phase

    /** 설치 세션에 넘겼다. 성공하면 앱이 새 버전으로 바뀐다. */
    data object Installing : Phase
    data class Failed(val message: String) : Phase

    /** 이번 실행에서는 더 조르지 않는다. */
    data object Postponed : Phase
}

/**
 * 앱을 켤 때마다 새 버전을 확인하고, 있으면 **받아서 설치까지 알아서 한다.**
 *
 * 사이드로드 배포라 Play Store가 대신 해 주지 않는다. 예전에는 "받아서 설치"를 눌러야 했는데,
 * 그러면 업데이트가 밀린다.
 *
 * - 와이파이: 묻지 않고 받고, 받는 대로 설치한다. 이 앱이 스스로를 한 번 설치한 뒤로는
 *   안드로이드 12부터 확인 화면도 없다([ApkInstaller.install]).
 * - 데이터 요금이 붙는 회선: 먼저 묻는다 — 로밍 중에 10MB를 말없이 받으면 안 된다.
 * - 연습 중: 받아만 두고 설치는 세션을 나간 뒤에 한다. 설치하면 앱이 닫히기 때문이다.
 *
 * **확인이 실패해도 화면을 막지 않는다.** 이유는 설정 화면에 남긴다.
 *
 * @param checkRequest 설정의 "지금 확인"을 누를 때마다 오른다. 간격과 무관하게 바로 확인한다.
 */
@Composable
internal fun UpdateGate(
    lastCheckedEpochMs: Long?,
    inSession: Boolean,
    checkRequest: Int,
    onChecked: (epochMs: Long, outcome: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 관찰자는 한 번만 등록한다. 그 안에서 읽는 값은 최신 것이어야 한다.
    val lastChecked by rememberUpdatedState(lastCheckedEpochMs)
    val latestOnChecked by rememberUpdatedState(onChecked)

    var update by remember { mutableStateOf<AvailableUpdate?>(null) }
    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
    var checking by remember { mutableStateOf(false) }
    // 미뤄 둔 같은 버전을 "지금 확인"으로 다시 시작할 때 받기 효과를 다시 돌리는 열쇠.
    var attempt by remember { mutableStateOf(0) }

    /**
     * 받기와 설치는 효과가 아니라 화면 수명의 스코프에서 돈다. 효과 안에서 돌리면 진행률로
     * 상태가 바뀌는 순간 효과가 다시 시작되며 받던 것이 취소된다.
     */
    fun startDownload(target: AvailableUpdate) {
        phase = Phase.Downloading(null)
        toast(context, "새 버전 v${target.version}을 내려받습니다")
        scope.launch {
            val file = ApkInstaller(context).download(target) { state ->
                when (state) {
                    is DownloadState.Running -> phase = Phase.Downloading(percent(state))
                    is DownloadState.Failed -> phase = Phase.Failed(state.message)
                    else -> Unit
                }
            }
            if (file != null) phase = Phase.Downloaded(file)
        }
    }

    fun startInstall(file: File) {
        phase = Phase.Installing
        scope.launch {
            // 10MB를 설치 세션에 복사한다. 화면 스레드에서 하면 멈춘다.
            val result = withContext(Dispatchers.IO) { ApkInstaller(context).install(file) }
            phase = when (result) {
                ApkInstaller.InstallResult.Committed -> {
                    toast(context, "새 버전을 설치합니다. 앱이 잠깐 닫힙니다.")
                    Phase.Installing
                }
                is ApkInstaller.InstallResult.Launch -> {
                    runCatching { context.startActivity(result.intent) }
                    Phase.Installing
                }
                is ApkInstaller.InstallResult.NeedsPermission ->
                    Phase.NeedsPermission(file, result.settingsIntent)
                is ApkInstaller.InstallResult.Failed -> Phase.Failed(result.message)
            }
        }
    }

    fun runCheck(manual: Boolean) {
        if (checking) return
        checking = true
        scope.launch {
            val now = System.currentTimeMillis()
            val result = UpdateChecker(
                repo = BuildConfig.UPDATE_REPO,
                currentVersion = BuildConfig.VERSION_NAME,
            ).check()
            checking = false
            // 결과가 나온 뒤에 적는다. 켜자마자 회선이 덜 올라와 실패했을 때 다음 복귀에서
            // 바로 다시 확인하도록.
            latestOnChecked(if (result is CheckResult.Failed) 0L else now, outcomeText(result))

            when (result) {
                is CheckResult.Available -> {
                    if (update?.version != result.update.version) {
                        update = result.update
                        phase = Phase.Idle
                    } else if (manual && (phase == Phase.Postponed || phase is Phase.Failed)) {
                        phase = Phase.Idle
                        attempt += 1
                    }
                }
                CheckResult.UpToDate -> if (manual) toast(context, "최신 버전입니다 (v${BuildConfig.VERSION_NAME})")
                is CheckResult.Failed -> if (manual) toast(context, "확인하지 못했습니다: ${result.reason}")
            }
        }
    }

    /**
     * 켤 때와 화면으로 돌아올 때 확인한다.
     *
     * `LaunchedEffect(Unit)`만 두면 앱이 메모리에 남아 있는 동안에는 다시 확인하지 않는다.
     * 업무 중에 며칠씩 켜 둔 앱이 새 버전을 영영 모르게 된다.
     */
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_START) return@LifecycleEventObserver

            // 설치 허용을 켜러 설정에 다녀왔다. 켰으면 받아 둔 파일을 이어서 설치한다.
            val waiting = phase
            if (waiting is Phase.NeedsPermission && canInstall(context)) {
                phase = Phase.Downloaded(waiting.file)
            }

            if (UpdatePolicy.shouldCheck(lastChecked, System.currentTimeMillis())) runCheck(manual = false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(checkRequest) {
        if (checkRequest > 0) runCheck(manual = true)
    }

    // 설치 결과 — 취소했으면 이번 실행에서는 그만 조르고, 실패했으면 이유를 보여 준다.
    LaunchedEffect(Unit) {
        InstallEvents.outcomes.collect { outcome ->
            phase = when (outcome) {
                InstallOutcome.Cancelled -> Phase.Postponed
                is InstallOutcome.Failed -> Phase.Failed(outcome.message)
            }
        }
    }

    // 새 버전을 찾았으면 받는다. 요금이 붙는 회선이면 먼저 묻는다.
    LaunchedEffect(update, attempt) {
        val pending = update ?: return@LaunchedEffect
        if (phase != Phase.Idle) return@LaunchedEffect
        if (UpdatePolicy.downloadsWithoutAsking(isMetered(context))) {
            startDownload(pending)
        } else {
            phase = Phase.AskMetered
        }
    }

    // 다 받았으면 설치한다. 연습 중이면 나갈 때까지 기다린다 — 설치하면 앱이 닫힌다.
    LaunchedEffect(phase, inSession) {
        val ready = phase as? Phase.Downloaded ?: return@LaunchedEffect
        if (!inSession) startInstall(ready.file)
    }

    val pending = update ?: return
    when (val current = phase) {
        Phase.AskMetered -> AlertDialog(
            onDismissRequest = { phase = Phase.Postponed },
            title = { Text("새 버전 v${pending.version}") },
            text = {
                Text(
                    "지금은 모바일 데이터입니다. ${sizeText(pending.sizeBytes)}를 받아서 설치할까요?\n" +
                        "와이파이에 연결되면 다음에 앱을 켤 때 자동으로 받습니다.",
                )
            },
            confirmButton = {
                TextButton(onClick = { startDownload(pending) }) { Text("지금 받기") }
            },
            dismissButton = {
                TextButton(onClick = { phase = Phase.Postponed }) { Text("와이파이에서") }
            },
        )

        is Phase.NeedsPermission -> AlertDialog(
            onDismissRequest = { phase = Phase.Postponed },
            title = { Text("새 버전 v${pending.version} 설치") },
            text = {
                Text(
                    "업데이트를 설치하려면 설정에서 \"이 출처 허용\"을 한 번 켜 주세요.\n" +
                        "켜고 돌아오면 바로 설치합니다. 한 번만 하면 됩니다.",
                )
            },
            confirmButton = {
                // 켜고 돌아오면 ON_START에서 이어서 설치한다.
                TextButton(onClick = { runCatching { context.startActivity(current.settingsIntent) } }) {
                    Text("설정 열기")
                }
            },
            dismissButton = {
                TextButton(onClick = { phase = Phase.Postponed }) { Text("나중에") }
            },
        )

        is Phase.Failed -> AlertDialog(
            onDismissRequest = { phase = Phase.Postponed },
            title = { Text("새 버전 v${pending.version}") },
            text = { Text("${current.message}\n릴리즈 페이지에서 직접 받을 수 있습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    openReleasePage(context, pending)
                    phase = Phase.Postponed
                }) { Text("릴리즈 페이지 열기") }
            },
            dismissButton = {
                TextButton(onClick = { phase = Phase.Postponed }) { Text("닫기") }
            },
        )

        // 받는 중·설치 중에는 화면을 막지 않는다. 진행은 시스템 알림에 보인다.
        else -> Unit
    }
}

private fun canInstall(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

/** 데이터 요금이 붙는 회선인가. 모르면 붙는다고 본다 — 말없이 받는 쪽이 더 위험하다. */
private fun isMetered(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
    return manager.isActiveNetworkMetered
}

private fun outcomeText(result: CheckResult): String = when (result) {
    is CheckResult.Available -> "v${result.update.version}이 나와 설치를 준비합니다"
    CheckResult.UpToDate -> "최신 버전입니다"
    is CheckResult.Failed -> "확인하지 못했습니다 (${result.reason})"
}

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

private fun openReleasePage(context: Context, update: AvailableUpdate) {
    val url = update.releaseUrl ?: return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun percent(state: DownloadState.Running): Int? =
    if (state.totalBytes <= 0) null else (state.downloadedBytes * 100 / state.totalBytes).toInt()

private fun sizeText(bytes: Long): String =
    if (bytes <= 0) "새 버전" else "약 ${bytes / (1024 * 1024)}MB"
