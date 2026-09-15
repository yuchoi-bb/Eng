package com.eng.shadowing.core.store

import com.eng.shadowing.core.daily.DailySpeechLog
import com.eng.shadowing.core.model.UserSettings
import com.eng.shadowing.core.model.VideoPlan
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * S0의 전체 저장 상태.
 *
 * S0는 외부 의존이 0이므로 Firestore를 쓰지 않는다(REQUIREMENTS §11.2). 대신 이 한 덩어리를
 * 기기 로컬에 JSON으로 적는다. **필드 이름과 구조는 FIRESTORE_SCHEMA.md를 그대로 따르므로**
 * S1에서 Firestore 구현으로 갈아탈 때 모델을 다시 만들 필요가 없다.
 *
 * 업로드 영상의 로컬 URI가 [localMediaUris]로 따로 빠져 있는 것이 핵심이다 —
 * 이 맵만 동기화 대상에서 제외하면 나머지는 그대로 올라간다(§10.1).
 */
@Serializable
public data class AppState(
    val schemaVersion: Int = SCHEMA_VERSION,
    val settings: UserSettings = UserSettings(),
    val plans: Map<String, VideoPlan> = emptyMap(),
    @Serializable(with = LocalDateKeyedLogsSerializer::class)
    val dailyLogs: Map<LocalDate, DailySpeechLog> = emptyMap(),
    /** `videoPlanId → content:// URI`. **기기 로컬 전용 — 절대 동기화하지 않는다.** */
    val localMediaUris: Map<String, String> = emptyMap(),
) {
    public companion object {
        public const val SCHEMA_VERSION: Int = 1

        public val json: Json = Json {
            ignoreUnknownKeys = true // 구버전 앱이 신버전 파일을 만나도 죽지 않는다
            encodeDefaults = true
            prettyPrint = true
        }
    }
}

/** ISO-8601 문자열로 적는다. 문서 ID 규칙(FIRESTORE_SCHEMA §1.2)과 같은 형식이다. */
public object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

/**
 * `Map<LocalDate, DailySpeechLog>`를 JSON 객체로 적는다.
 *
 * kotlinx.serialization은 기본적으로 non-primitive 키를 가진 맵을 배열로 적는데,
 * 그러면 날짜 키로 직접 들여다볼 수 없는 파일이 된다. 키를 문자열로 낮춰 둔다.
 */
public object LocalDateKeyedLogsSerializer : KSerializer<Map<LocalDate, DailySpeechLog>> {
    private val delegate = MapSerializer(String.serializer(), DailySpeechLog.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Map<LocalDate, DailySpeechLog>) {
        delegate.serialize(encoder, value.mapKeys { it.key.toString() })
    }

    override fun deserialize(decoder: Decoder): Map<LocalDate, DailySpeechLog> =
        delegate.deserialize(decoder).mapKeys { LocalDate.parse(it.key) }
}
