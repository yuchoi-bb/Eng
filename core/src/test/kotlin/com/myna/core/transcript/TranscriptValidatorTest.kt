package com.myna.core.transcript

import com.myna.core.model.Cefr
import com.myna.core.model.VideoType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** TRANSCRIPTION_SCHEMA §4.2의 V-1 ~ V-9. */
class TranscriptValidatorTest {

    private fun raw(
        language: String? = "en",
        type: String? = "DIALOGUE",
        cefr: String? = "A2",
        sentences: List<RawSentence> = listOf(okSentence(0)),
        warnings: List<String> = emptyList(),
    ) = RawTranscript(
        schemaVersion = 1,
        source = "UPLOAD",
        sourceRef = "abc",
        language = language,
        type = type,
        cefr = cefr,
        timestampUnit = "MILLISECOND",
        speechStartMs = 0,
        speechEndMs = 4_000,
        sentences = sentences,
        warnings = warnings,
    )

    private fun okSentence(
        index: Int,
        text: String = "What are you up to this weekend?",
        startMs: Int = 0,
        endMs: Int = 2_000,
        keywords: List<String> = listOf("up to", "weekend"),
    ) = RawSentence(index, text, "이번 주말에 뭐 해?", null, startMs, endMs, keywords)

    @Test
    fun `V-1 문장이 비어 있으면 등록을 거부한다`() {
        val result = TranscriptValidator.validate(raw(sentences = emptyList()))
        assertEquals(RejectionReason.EMPTY_SENTENCES, assertIs<ValidationResult.Rejected>(result).reason)
    }

    @Test
    fun `V-9 영어가 아니면 등록을 거부한다`() {
        val result = TranscriptValidator.validate(raw(language = "ko"))
        assertEquals(RejectionReason.NOT_ENGLISH, assertIs<ValidationResult.Rejected>(result).reason)
    }

    @Test
    fun `V-2 시작이 끝보다 늦은 문장은 제외한다`() {
        val result = TranscriptValidator.validate(
            raw(sentences = listOf(okSentence(0), okSentence(1, startMs = 5_000, endMs = 3_000))),
        )
        val accepted = assertIs<ValidationResult.Accepted>(result)
        assertEquals(1, accepted.transcript.sentences.size)
        assertTrue(accepted.repairs.any { it is Repair.DroppedInvalidRange })
    }

    @Test
    fun `V-2 모든 문장이 탈락하면 거부한다`() {
        // §4.3 3번 — 전사는 성공했으나 검증에서 전부 떨어진 경우.
        val result = TranscriptValidator.validate(
            raw(sentences = listOf(okSentence(0, startMs = 9_000, endMs = 1_000))),
        )
        assertEquals(RejectionReason.ALL_SENTENCES_DROPPED, assertIs<ValidationResult.Rejected>(result).reason)
    }

    @Test
    fun `V-3 시작 시각이 뒤섞이면 정렬하고 다시 번호를 매긴다`() {
        val result = TranscriptValidator.validate(
            raw(
                sentences = listOf(
                    okSentence(0, text = "second one here", startMs = 3_000, endMs = 4_000),
                    okSentence(1, text = "first one here", startMs = 0, endMs = 2_000),
                ),
            ),
        )
        val accepted = assertIs<ValidationResult.Accepted>(result)
        assertContains(accepted.repairs, Repair.ReorderedByStartTime)
        assertEquals(listOf(0, 1), accepted.transcript.sentences.map { it.index })
        assertEquals("first one here", accepted.transcript.sentences[0].text)
    }

    @Test
    fun `V-4 영상 길이를 넘는 타임스탬프는 클램프한다`() {
        val result = TranscriptValidator.validate(
            raw(sentences = listOf(okSentence(0, startMs = 0, endMs = 99_000))),
            videoDurationMs = 10_000,
        )
        val accepted = assertIs<ValidationResult.Accepted>(result)
        assertEquals(10_000, accepted.transcript.sentences.single().endMs)
        assertTrue(accepted.repairs.any { it is Repair.ClampedToVideoDuration })
    }

    @Test
    fun `V-5 문장에 없는 키워드는 제거한다`() {
        // 환각 방지 — TRANSCRIPTION_SCHEMA §3.2.
        val result = TranscriptValidator.validate(
            raw(sentences = listOf(okSentence(0, keywords = listOf("weekend", "vacation")))),
        )
        val accepted = assertIs<ValidationResult.Accepted>(result)
        assertEquals(listOf("weekend"), accepted.transcript.sentences.single().keywords)
        assertTrue(accepted.repairs.any { it is Repair.RemovedHallucinatedKeywords })
    }

    @Test
    fun `V-6 키워드가 하나도 안 남으면 내용어로 대체한다`() {
        val result = TranscriptValidator.validate(
            raw(sentences = listOf(okSentence(0, text = "The weekend sounds wonderful", keywords = listOf("nope")))),
        )
        val accepted = assertIs<ValidationResult.Accepted>(result)
        val keywords = accepted.transcript.sentences.single().keywords
        assertEquals(2, keywords.size)
        // 긴 내용어 우선 — wonderful(9) > weekend(7) > sounds(6). 관사 the는 기능어라 제외된다.
        assertEquals(listOf("wonderful", "weekend"), keywords)
        assertTrue(accepted.repairs.any { it is Repair.SubstitutedKeywords })
    }

    @Test
    fun `V-7 알 수 없는 enum은 폴백한다`() {
        val result = TranscriptValidator.validate(
            raw(type = "MONOLOGUE", cefr = "Z9", warnings = listOf("MUSIC_HEAVY", "MADE_UP")),
        )
        val accepted = assertIs<ValidationResult.Accepted>(result)
        assertEquals(VideoType.DIALOGUE, accepted.transcript.type)
        assertEquals(Cefr.B1, accepted.transcript.cefr)
        assertEquals(1, accepted.transcript.warnings.size) // 모르는 warning은 무시
        assertTrue(accepted.repairs.any { it is Repair.IgnoredWarning })
    }

    @Test
    fun `V-8 SINGLE인데 호흡 그룹이 없으면 DIALOGUE로 강등한다`() {
        val result = TranscriptValidator.validate(raw(type = "SINGLE"))
        val accepted = assertIs<ValidationResult.Accepted>(result)
        assertEquals(VideoType.DIALOGUE, accepted.transcript.type)
        assertContains(accepted.repairs, Repair.DemotedSingleWithoutBreathGroups)
    }

    @Test
    fun `V-8 호흡 그룹이 있으면 SINGLE을 유지한다`() {
        val result = TranscriptValidator.validate(
            raw(type = "SINGLE").copy(
                sentences = listOf(
                    okSentence(0).copy(
                        breathGroups = listOf(
                            RawBreathGroup("What are you up to", 0, 1_200),
                            RawBreathGroup("this weekend?", 1_200, 2_000),
                        ),
                    ),
                ),
            ),
        )
        val accepted = assertIs<ValidationResult.Accepted>(result)
        assertEquals(VideoType.SINGLE, accepted.transcript.type)
        assertEquals(2, accepted.transcript.sentences.single().breathGroups.size)
    }

    @Test
    fun `정상 응답은 수선 없이 통과한다`() {
        val accepted = assertIs<ValidationResult.Accepted>(TranscriptValidator.validate(raw()))
        assertTrue(accepted.repairs.isEmpty(), "예상치 못한 수선: ${accepted.repairs}")
    }

    @Test
    fun `모르는 상위 스키마 버전은 거부한다`() {
        val result = TranscriptValidator.validate(raw().copy(schemaVersion = 2))
        assertEquals(RejectionReason.UNSUPPORTED_SCHEMA_VERSION, assertIs<ValidationResult.Rejected>(result).reason)
    }
}
