package com.myna.core.notes

import kotlinx.serialization.Serializable

/**
 * 현장에서 막힌 순간의 기록.
 *
 * 실제로 말하려다 막혔거나, 말했는데 못 알아들은 순간 — 그때가 가장 좋은 학습 소재인데
 * 지금까지는 그냥 흘려보냈다. 한국어로 한 줄만 적어 두었다가 나중에 영어 문장을 채우면
 * 연습 대상이 된다.
 *
 * **기록은 한국어로 받는다.** 영어로 적을 수 있었다면 애초에 막히지 않았다.
 */
@Serializable
public data class FieldNote(
    val id: String,
    /** "택시에서 목적지 바꿔 달라고 못 함" 같은 한 줄. */
    val koreanMemo: String,
    /** 어디서 막혔는지. 상황별로 모아 보기 위한 태그. */
    val situation: NoteSituation = NoteSituation.OTHER,
    /** 나중에 채우는 영어 문장. 채워지면 연습 대상이 된다. */
    val englishText: String? = null,
    val createdAtEpochMs: Long,
    val resolvedAtEpochMs: Long? = null,
    /** 연습 계획으로 만들어졌다면 그 id. */
    val practicePlanId: String? = null,
) {
    val isResolved: Boolean get() = !englishText.isNullOrBlank()
}

/** 업무 여행에서 실제로 막히는 자리들. */
@Serializable
public enum class NoteSituation {
    AIRPORT,
    HOTEL,
    RESTAURANT,
    TAXI,
    MEETING,
    SMALL_TALK,
    SHOPPING,
    TROUBLE,
    OTHER,
}
