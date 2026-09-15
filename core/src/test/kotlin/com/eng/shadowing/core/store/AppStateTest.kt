package com.eng.shadowing.core.store

import com.eng.shadowing.core.daily.DailySpeechLog
import com.eng.shadowing.core.model.UserSettings
import com.eng.shadowing.core.plan
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** S0 저장 형식. S1에서 Firestore로 갈아탈 때 이 구조가 그대로 문서가 된다. */
class AppStateTest {

    private val date = LocalDate.of(2026, 9, 15)

    private fun sample(): AppState {
        val videoPlan = plan(targetReps = 8)
        return AppState(
            settings = UserSettings(dailyTargetSec = 600, playbackRate = 0.75f),
            plans = mapOf(videoPlan.id.value to videoPlan),
            dailyLogs = mapOf(date to DailySpeechLog(date, dailyTargetSec = 600, achievedSec = 320)),
            localMediaUris = mapOf(videoPlan.id.value to "content://media/external/video/42"),
        )
    }

    @Test
    fun `저장했다 읽으면 같은 값이 나온다`() {
        val original = sample()
        val restored = AppState.json.decodeFromString(
            AppState.serializer(),
            AppState.json.encodeToString(AppState.serializer(), original),
        )
        assertEquals(original, restored)
    }

    @Test
    fun `날짜 키는 ISO 문자열로 적힌다`() {
        // FIRESTORE_SCHEMA §1.2 — 일일 로그의 문서 ID와 같은 형식이라야
        // S1에서 파일을 그대로 문서로 옮길 수 있다.
        val encoded = AppState.json.encodeToString(AppState.serializer(), sample())
        assertTrue(encoded.contains("\"2026-09-15\""), encoded)
    }

    @Test
    fun `모르는 필드가 있어도 읽는다`() {
        // 구버전 앱이 신버전 파일을 만나 죽지 않아야 한다.
        val withExtra = """{"schemaVersion":1,"futureField":{"a":1},"settings":{"dailyTargetSec":300}}"""
        val restored = AppState.json.decodeFromString(AppState.serializer(), withExtra)
        assertEquals(300, restored.settings.dailyTargetSec)
    }

    @Test
    fun `로컬 미디어 URI는 계획과 분리되어 있다`() {
        // §10.1 — content:// URI는 그 기기의 그 설치본에만 유효하므로
        // 동기화 대상인 VideoPlan 안에 들어가면 안 된다.
        val state = sample()
        val planJson = AppState.json.encodeToString(AppState.serializer(), state.copy(localMediaUris = emptyMap()))
        assertTrue(!planJson.contains("content://"), "VideoPlan이 로컬 URI를 들고 있다")
    }
}
