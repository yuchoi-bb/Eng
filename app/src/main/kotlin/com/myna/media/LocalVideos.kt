package com.myna.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * 기기에서 고른 영상을 앱 안에 보관한다 (F-2, REQUIREMENTS §10.1).
 *
 * 사진 선택기나 공유로 받은 `content://` 주소는 **잠깐 빌려 준 것**이다. 앱이 다시 시작되면
 * (백그라운드에서 닫히거나 업데이트되면) 같은 주소로 영상을 열 수 없다. 주소만 저장해 두면
 * 문장은 다 받아 놓고 연습할 때 영상이 나오지 않는다 — 실제로 그렇게 됐다.
 *
 * 그래서 저장할 때 앱 전용 저장소로 복사한다. 갤러리에서 지워도, 권한이 끊겨도 재생된다.
 * 쇼츠는 수십 MB라 부담이 작다. 이 파일은 기기 로컬이며 동기화하지 않는다.
 */
internal object LocalVideos {

    private const val TAG = "LocalVideos"
    private const val DIR = "videos"

    /**
     * [source]를 앱 저장소로 복사하고 그 파일의 URI를 돌려준다. 실패하면 null.
     * 이미 앱 저장소에 있는 파일이면 그대로 돌려준다.
     */
    fun import(context: Context, source: Uri, planId: String): Uri? {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val target = File(dir, "$planId.video")
        if (source.scheme == "file" && source.path == target.path) return source

        // 다 복사하기 전에 끊겨도 반쪽 파일이 남지 않게 임시 파일에 쓰고 바꿔 단다.
        val temp = File(dir, "$planId.part")
        return runCatching {
            val copied = context.contentResolver.openInputStream(source)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }
            if (copied == null || copied <= 0L) {
                temp.delete()
                return null
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) error("파일 이름을 바꾸지 못했습니다")
            }
            Uri.fromFile(target)
        }.getOrElse { error ->
            Log.w(TAG, "영상을 앱에 복사하지 못했습니다: $source", error)
            temp.delete()
            null
        }
    }

    /** 이 기기에서 지금 열 수 있는가. */
    fun canRead(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    /**
     * 사진 선택기가 준 주소를 앱이 다시 시작돼도 열 수 있게 붙잡아 둔다.
     *
     * 고른 뒤 분석이 끝날 때까지 1분 넘게 걸리는 동안 앱이 백그라운드에서 닫혀도 저장 단계에서
     * 복사할 수 있게 하려는 것이다. 지원하지 않는 주소면 조용히 넘어간다.
     */
    fun keepAccess(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
