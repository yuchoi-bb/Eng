package com.myna.core.store

import com.myna.core.daily.DailySpeechLog
import com.myna.core.model.UserSettings
import com.myna.core.plan
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun `이어서 하기 자리와 외운 문장이 저장된다`() {
        val videoPlan = plan(targetReps = 8).copy(
            resumeAt = com.myna.core.model.PlanPosition(sentenceIndex = 3, repIndex = 5),
            completedRounds = 1,
            memorizedSentences = setOf(0, 2),
        )
        val original = AppState(plans = mapOf(videoPlan.id.value to videoPlan))
        val restored = AppState.json.decodeFromString(
            AppState.serializer(),
            AppState.json.encodeToString(AppState.serializer(), original),
        )
        assertEquals(original, restored)
    }

    @Test
    fun `이전 버전 파일의 계획은 처음부터로 읽힌다`() {
        // v0.5 이하가 적은 파일에는 resumeAt·memorizedSentences가 없다.
        val encoded = AppState.json.encodeToString(AppState.serializer(), sample())
            .lines()
            .filterNot { "\"resumeAt\"" in it || "\"memorizedSentences\"" in it || "\"completedRounds\"" in it }
            .joinToString("\n")
            // 마지막 필드를 지우면 앞 줄에 쉼표가 남는다.
            .replace(Regex(",(\\s*})"), "$1")
        assertTrue("resumeAt" !in encoded && "memorizedSentences" !in encoded, encoded)
        val restored = AppState.json.decodeFromString(AppState.serializer(), encoded)
        val videoPlan = restored.plans.values.single()
        assertNull(videoPlan.resumeAt)
        assertEquals(emptySet(), videoPlan.memorizedSentences)
    }
}
