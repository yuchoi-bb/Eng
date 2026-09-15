package com.eng.shadowing.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.eng.shadowing.core.recording.RecordingRef
import com.eng.shadowing.core.recording.RecordingRetention
import java.io.File

/**
 * 문장 녹음. REQUIREMENTS §4.5.
 *
 * 보관 규칙(최근 3회분 순환 + 최초 1개 영구)은 [RecordingRetention]이 판단하고,
 * 여기서는 그 결정을 파일 시스템에 반영하기만 한다.
 */
public class SentenceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    public fun start(sentenceId: String) {
        stop() // 앞선 녹음이 살아 있으면 정리한다

        val directory = File(context.filesDir, "recordings/$sentenceId").apply { mkdirs() }
        val target = File(directory, "${System.currentTimeMillis()}.m4a")

        val instance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        runCatching {
            instance.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(target.absolutePath)
                prepare()
                start()
            }
            recorder = instance
            currentFile = target
        }.onFailure { error ->
            Log.e(TAG, "녹음을 시작하지 못했습니다.", error)
            runCatching { instance.release() }
            recorder = null
            currentFile = null
        }
    }

    /**
     * 녹음을 끝내고 보관 규칙을 적용한다.
     *
     * @return 이번 녹음. 녹음이 시작되지 않았으면 null.
     */
    public fun stop(sentenceId: String? = null): RecordingRef? {
        val instance = recorder ?: return null
        val file = currentFile
        recorder = null
        currentFile = null

        runCatching { instance.stop() }.onFailure {
            // 너무 짧게 끊으면 stop()이 던진다. 파일은 쓸 수 없으므로 버린다.
            Log.w(TAG, "녹음이 너무 짧아 저장하지 않습니다.", it)
            runCatching { instance.release() }
            file?.delete()
            return null
        }
        instance.release()

        if (file == null || sentenceId == null) return null

        val incoming = RecordingRef(path = file.absolutePath, recordedAtEpochMs = System.currentTimeMillis())
        val decision = RecordingRetention.apply(existing = existingFor(sentenceId), incoming = incoming)
        decision.delete.forEach { File(it.path).delete() }
        return incoming
    }

    /** L1 A/B 비교용 — 이 문장의 최근 녹음. */
    public fun latestRecording(sentenceId: String): File? =
        recordingDirectory(sentenceId).listFiles()?.maxByOrNull { it.lastModified() }

    private fun existingFor(sentenceId: String): List<RecordingRef> {
        val files = recordingDirectory(sentenceId).listFiles()?.sortedBy { it.lastModified() } ?: return emptyList()
        return files.mapIndexed { index, file ->
            RecordingRef(
                path = file.absolutePath,
                recordedAtEpochMs = file.lastModified(),
                // 가장 오래된 것이 그 문장의 최초 녹음이다 — §4.5 예외 규칙, F-9가 여기 의존한다.
                isFirstEver = index == 0,
            )
        }
    }

    private fun recordingDirectory(sentenceId: String) = File(context.filesDir, "recordings/$sentenceId")

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val BIT_RATE = 96_000
        const val TAG = "SentenceRecorder"
    }
}
