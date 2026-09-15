package com.eng.shadowing.core.transcript

import com.eng.shadowing.core.model.BreathGroup
import com.eng.shadowing.core.model.Cefr
import com.eng.shadowing.core.model.Expression
import com.eng.shadowing.core.model.Sentence
import com.eng.shadowing.core.model.TimestampUnit
import com.eng.shadowing.core.model.Transcript
import com.eng.shadowing.core.model.TranscriptWarning
import com.eng.shadowing.core.model.VideoSource
import com.eng.shadowing.core.model.VideoType

/** 검증 결과. 거부되면 저장하지 않는다 — TRANSCRIPTION_SCHEMA §4.1. */
public sealed interface ValidationResult {
    public data class Accepted(
        val transcript: Transcript,
        val repairs: List<Repair>,
    ) : ValidationResult

    public data class Rejected(val reason: RejectionReason) : ValidationResult
}

/** 등록 자체를 거부하는 사유. 사용자에게 안내하고 수동 입력 경로로 보낸다(§4.3). */
public enum class RejectionReason {
    /** V-1 */
    EMPTY_SENTENCES,

    /** V-9 */
    NOT_ENGLISH,

    /** §4.3 3번 — 전사는 됐으나 검증에서 문장이 모두 탈락. */
    ALL_SENTENCES_DROPPED,

    MISSING_SOURCE_REF,
    UNSUPPORTED_SCHEMA_VERSION,
}

/** 저장은 하되 사용자에게 무엇을 고쳤는지 보여줄 수 있도록 남기는 수선 기록. */
public sealed interface Repair {
    /** V-2 */
    public data class DroppedInvalidRange(val index: Int) : Repair

    /** V-3 */
    public data object ReorderedByStartTime : Repair

    /** V-4 */
    public data class ClampedToVideoDuration(val index: Int) : Repair

    /** V-5 */
    public data class RemovedHallucinatedKeywords(val index: Int, val removed: List<String>) : Repair

    /** V-6 */
    public data class SubstitutedKeywords(val index: Int, val substituted: List<String>) : Repair

    /** V-7 */
    public data class FellBackEnum(val field: String, val raw: String?, val fallback: String) : Repair

    /** V-7 — 알 수 없는 warning은 무시한다. */
    public data class IgnoredWarning(val raw: String) : Repair

    /** V-8 */
    public data object DemotedSingleWithoutBreathGroups : Repair
}

/**
 * TRANSCRIPTION_SCHEMA §4.2의 V-1 ~ V-9를 구현한다.
 *
 * 순서가 중요하다. 거부 조건(V-1, V-9)을 먼저 보고, 그 다음 문장을 수선하고,
 * 수선 결과 문장이 모두 사라졌는지 마지막에 다시 본다.
 */
public object TranscriptValidator {

    public const val SUPPORTED_SCHEMA_VERSION: Int = 1

    public fun validate(
        raw: RawTranscript,
        videoDurationMs: Int? = null,
    ): ValidationResult {
        val repairs = mutableListOf<Repair>()

        // V-9 — 비영어는 등록 거부.
        if (!raw.language.equals("en", ignoreCase = true)) {
            return ValidationResult.Rejected(RejectionReason.NOT_ENGLISH)
        }
        // V-1 — 문장이 하나도 없으면 거부.
        if (raw.sentences.isEmpty()) {
            return ValidationResult.Rejected(RejectionReason.EMPTY_SENTENCES)
        }
        val sourceRef = raw.sourceRef?.takeIf { it.isNotBlank() }
            ?: return ValidationResult.Rejected(RejectionReason.MISSING_SOURCE_REF)

        val schemaVersion = raw.schemaVersion ?: SUPPORTED_SCHEMA_VERSION
        if (schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            return ValidationResult.Rejected(RejectionReason.UNSUPPORTED_SCHEMA_VERSION)
        }

        // V-7 — enum 폴백.
        val source = enumOrNull<VideoSource>(raw.source) ?: VideoSource.UPLOAD
        var type = enumOrNull<VideoType>(raw.type) ?: run {
            repairs += Repair.FellBackEnum("type", raw.type, VideoType.DIALOGUE.name)
            VideoType.DIALOGUE
        }
        val cefr = enumOrNull<Cefr>(raw.cefr) ?: run {
            repairs += Repair.FellBackEnum("cefr", raw.cefr, Cefr.B1.name)
            Cefr.B1
        }
        val timestampUnit = enumOrNull<TimestampUnit>(raw.timestampUnit) ?: run {
            repairs += Repair.FellBackEnum("timestampUnit", raw.timestampUnit, TimestampUnit.MILLISECOND.name)
            TimestampUnit.MILLISECOND
        }
        val warnings = raw.warnings.mapNotNull { rawWarning ->
            enumOrNull<TranscriptWarning>(rawWarning).also {
                if (it == null) repairs += Repair.IgnoredWarning(rawWarning)
            }
        }

        // V-2 — startMs < endMs 위반 문장 제외.
        val surviving = raw.sentences.filter { sentence ->
            val ok = sentence.text?.isNotBlank() == true &&
                sentence.startMs != null &&
                sentence.endMs != null &&
                sentence.startMs < sentence.endMs
            if (!ok) repairs += Repair.DroppedInvalidRange(sentence.index ?: -1)
            ok
        }
        if (surviving.isEmpty()) {
            return ValidationResult.Rejected(RejectionReason.ALL_SENTENCES_DROPPED)
        }

        // V-3 — startMs 단조 증가. 깨져 있으면 정렬한다.
        val alreadySorted = surviving.zipWithNext().all { (a, b) -> a.startMs!! <= b.startMs!! }
        val ordered = if (alreadySorted) {
            surviving
        } else {
            repairs += Repair.ReorderedByStartTime
            surviving.sortedBy { it.startMs!! }
        }

        val sentences = ordered.mapIndexed { position, rawSentence ->
            buildSentence(position, rawSentence, videoDurationMs, repairs)
        }

        // V-8 — SINGLE인데 breathGroups가 없으면 DIALOGUE로 강등.
        if (type == VideoType.SINGLE && sentences.none { it.breathGroups.isNotEmpty() }) {
            repairs += Repair.DemotedSingleWithoutBreathGroups
            type = VideoType.DIALOGUE
        }

        val speechStart = raw.speechStartMs ?: sentences.first().startMs
        val speechEnd = raw.speechEndMs ?: sentences.last().endMs

        return ValidationResult.Accepted(
            transcript = Transcript(
                schemaVersion = schemaVersion,
                source = source,
                sourceRef = sourceRef,
                language = "en",
                type = type,
                cefr = cefr,
                timestampUnit = timestampUnit,
                speechStartMs = speechStart,
                speechEndMs = speechEnd,
                sentences = sentences,
                expressions = raw.expressions.mapNotNull { expression ->
                    val text = expression.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Expression(text, expression.meaningKo.orEmpty())
                },
                warnings = warnings,
            ),
            repairs = repairs,
        )
    }

    private fun buildSentence(
        position: Int,
        raw: RawSentence,
        videoDurationMs: Int?,
        repairs: MutableList<Repair>,
    ): Sentence {
        val text = raw.text!!.trim()
        var start = raw.startMs!!
        var end = raw.endMs!!

        // V-4 — 영상 길이를 넘는 타임스탬프는 클램프.
        if (videoDurationMs != null && (end > videoDurationMs || start > videoDurationMs)) {
            repairs += Repair.ClampedToVideoDuration(position)
            start = start.coerceAtMost(videoDurationMs)
            end = end.coerceAtMost(videoDurationMs)
        }

        // V-5 — text에 실제로 등장하지 않는 keyword 제거.
        val normalizedText = Keywords.normalize(text)
        val (kept, removed) = raw.keywords
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .partition { Keywords.appearsIn(normalizedText, it) }
        if (removed.isNotEmpty()) {
            repairs += Repair.RemovedHallucinatedKeywords(position, removed)
        }

        // V-6 — 하나도 남지 않으면 내용어 상위 2개로 로컬 대체.
        val keywords = kept.ifEmpty {
            Keywords.topContentWords(text, limit = 2).also {
                repairs += Repair.SubstitutedKeywords(position, it)
            }
        }

        return Sentence(
            index = position,
            text = text,
            translationKo = raw.translationKo.orEmpty(),
            startMs = start,
            endMs = end,
            keywords = keywords,
            transliterationKo = raw.transliterationKo?.takeIf { it.isNotBlank() },
            breathGroups = raw.breathGroups.mapNotNull { group ->
                val groupText = group.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val groupStart = group.startMs ?: return@mapNotNull null
                val groupEnd = group.endMs ?: return@mapNotNull null
                if (groupStart >= groupEnd) return@mapNotNull null
                // 그룹 타임스탬프 합은 문장 범위를 벗어나지 않아야 한다 — §3.2.
                BreathGroup(groupText, groupStart.coerceAtLeast(start), groupEnd.coerceAtMost(end))
            },
        )
    }

    private inline fun <reified E : Enum<E>> enumOrNull(raw: String?): E? {
        val value = raw?.trim()?.uppercase() ?: return null
        return enumValues<E>().firstOrNull { it.name == value }
    }
}
