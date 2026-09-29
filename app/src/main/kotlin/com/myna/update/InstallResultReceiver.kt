package com.myna.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 설치 세션이 끝났을 때 화면에 알릴 것. 성공은 알릴 필요가 없다 — 앱이 새 버전으로 바뀐다. */
internal sealed interface InstallOutcome {
    /** 사용자가 설치 확인 화면에서 취소했다. 이번 실행에서는 다시 조르지 않는다. */
    data object Cancelled : InstallOutcome
    data class Failed(val message: String) : InstallOutcome
}

/** 리시버와 화면 사이의 통로. 리시버는 화면을 모른다. */
internal object InstallEvents {
    private val events = MutableSharedFlow<InstallOutcome>(extraBufferCapacity = 4)
    val outcomes: SharedFlow<InstallOutcome> = events.asSharedFlow()

    fun report(outcome: InstallOutcome) {
        events.tryEmit(outcome)
    }
}

/**
 * [PackageInstaller] 세션의 결과를 받는다.
 *
 * 확인이 필요하면 시스템이 확인 화면 인텐트를 준다 — 그걸 띄우는 것이 여기 일이다.
 * 앱이 브라우저로 설치된 뒤 처음 스스로를 업데이트할 때, 그리고 안드로이드 11 이하에서는
 * 매번 이 경로로 온다.
 */
internal class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 이 리시버는 내보내지 않는다(exported=false). 이 인텐트는 시스템이 채운 것이다.
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    InstallEvents.report(InstallOutcome.Failed("설치 확인 화면을 받지 못했습니다."))
                    return
                }
                runCatching {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { error ->
                    Log.w(TAG, "설치 확인 화면을 띄우지 못했습니다.", error)
                    InstallEvents.report(InstallOutcome.Failed("설치 확인 화면을 띄우지 못했습니다."))
                }
            }

            // 성공하면 이 프로세스는 곧 새 버전으로 바뀐다. 할 일이 없다.
            PackageInstaller.STATUS_SUCCESS -> Unit

            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallEvents.report(InstallOutcome.Cancelled)

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "설치 실패 status=$status message=$message")
                InstallEvents.report(
                    InstallOutcome.Failed("설치하지 못했습니다" + (message?.let { " ($it)" } ?: "")),
                )
            }
        }
    }

    private companion object {
        const val TAG = "InstallResultReceiver"
    }
}
