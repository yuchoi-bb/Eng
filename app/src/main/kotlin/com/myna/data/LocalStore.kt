package com.myna.data

import android.util.Log
import com.myna.core.store.AppState
import java.io.File

/**
 * S0의 영속 계층 — `filesDir`의 JSON 파일 하나.
 *
 * REQUIREMENTS §11.2대로 S0는 외부 의존이 0이므로 Firestore를 쓰지 않는다. 저장 형식은
 * [AppState]가 들고 있고 FIRESTORE_SCHEMA.md의 구조를 그대로 따르므로, S1에서
 * 구현만 바꾸면 된다.
 *
 * **쓰기는 임시 파일에 적고 원자적으로 갈아치운다.** 세션 도중 앱이 죽으면 반쯤 적힌
 * JSON이 남아 다음 실행에서 학습 기록이 통째로 날아간다.
 */
public class LocalStore(private val filesDir: File) {

    private val file: File get() = File(filesDir, FILE_NAME)

    public fun load(): AppState {
        val current = file
        if (!current.exists()) return AppState()
        return runCatching {
            AppState.json.decodeFromString(AppState.serializer(), current.readText())
        }.getOrElse { error ->
            // 파일이 깨졌으면 백업으로 밀어 두고 빈 상태로 시작한다. 조용히 지우지 않는다.
            Log.e(TAG, "저장 파일을 읽지 못했습니다. ${BACKUP_NAME}으로 옮깁니다.", error)
            runCatching { current.copyTo(File(filesDir, BACKUP_NAME), overwrite = true) }
            AppState()
        }
    }

    public fun save(state: AppState) {
        val temp = File(filesDir, "$FILE_NAME.tmp")
        runCatching {
            temp.writeText(AppState.json.encodeToString(AppState.serializer(), state))
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }.onFailure { Log.e(TAG, "저장에 실패했습니다.", it) }
    }

    private companion object {
        const val FILE_NAME = "app-state.json"
        const val BACKUP_NAME = "app-state.corrupt.json"
        const val TAG = "LocalStore"
    }
}
