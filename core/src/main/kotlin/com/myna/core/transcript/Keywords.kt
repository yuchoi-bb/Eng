package com.myna.core.transcript

/**
 * 키워드 정규화와 로컬 대체. TRANSCRIPTION_SCHEMA §3.2 / V-5 / V-6.
 *
 * 키워드는 L2 부분 점수 채점의 기준이다(§6). 여기서 틀리면 왕초보가 맞게 말하고도
 * 오답 처리되므로, 대소문자와 구두점은 무시하되 **단어 경계는 지킨다.**
 */
public object Keywords {

    /**
     * 비교용 정규화: 소문자로 낮추고, 문자·숫자·아포스트로피만 남기고, 공백을 하나로 접는다.
     * 앞뒤에 공백을 붙여 두어 [appearsIn]이 부분 단어 일치를 걸러낼 수 있게 한다.
     */
    public fun normalize(text: String): String =
        " " + text.lowercase().replace(NON_WORD, " ").trim().replace(MULTI_SPACE, " ") + " "

    /**
     * 정규화된 문장 안에 키워드가 **단어 단위로** 등장하는지.
     *
     * 단순 `contains`를 쓰면 `"up"`이 `"upset"`에 걸린다. 양쪽을 공백으로 감싸서 비교한다.
     * 다중 단어 표현(`"up to"`)도 같은 방식으로 처리된다.
     */
    public fun appearsIn(normalizedText: String, keyword: String): Boolean {
        val normalizedKeyword = normalize(keyword)
        if (normalizedKeyword.isBlank()) return false
        return normalizedText.contains(normalizedKeyword)
    }

    /**
     * V-6 로컬 대체 — 내용어 상위 [limit]개.
     *
     * "상위"의 기준이 명세에 없어 **긴 단어 우선, 동률이면 먼저 나온 것**으로 정한다.
     * 왕초보에게는 기능어보다 긴 내용어가 의미의 중심일 확률이 높고, 이 규칙은
     * 결정적이라 같은 문장에서 항상 같은 결과가 나온다.
     *
     * **빈 목록을 돌려주지 않는다.** `"It is the one"`처럼 기능어만으로 된 문장이 실제로
     * 있고, 그런 문장에서 키워드가 0개가 되면 두 곳이 동시에 깨진다 — L2가 채점할 기준을
     * 잃고(§6), `firestore.rules`의 `keywords.size() > 0`에 걸려 저장도 거부된다.
     * 내용어가 하나도 없으면 기능어 중에서라도 고른다. 문장을 통째로 버리는 것보다 낫다.
     */
    public fun topContentWords(text: String, limit: Int): List<String> {
        val tokens = normalize(text).trim().split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val contentWords = tokens.filter { it !in STOP_WORDS }
        return rankByLength(contentWords.ifEmpty { tokens }, limit)
    }

    private fun rankByLength(words: List<String>, limit: Int): List<String> =
        words.withIndex()
            .distinctBy { it.value }
            .sortedWith(compareByDescending<IndexedValue<String>> { it.value.length }.thenBy { it.index })
            .take(limit)
            .map { it.value }

    private val NON_WORD = Regex("[^\\p{L}\\p{N}']+")
    private val MULTI_SPACE = Regex("\\s+")

    /**
     * 관사·전치사·대명사·조동사 등 기능어. §3.2의 "관사·전치사 단독 금지"를 구현한다.
     * 다중 단어 표현(`be up to`)은 LLM이 주는 것이므로 여기서 만들지 않는다.
     */
    private val STOP_WORDS: Set<String> = setOf(
        "a", "an", "the",
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them",
        "my", "your", "his", "its", "our", "their", "this", "that", "these", "those",
        "am", "is", "are", "was", "were", "be", "been", "being",
        "do", "does", "did", "done", "have", "has", "had",
        "will", "would", "can", "could", "shall", "should", "may", "might", "must",
        "to", "of", "in", "on", "at", "for", "with", "from", "by", "up", "out", "off",
        "about", "into", "over", "after", "before", "as", "than", "then",
        "and", "or", "but", "so", "if", "not", "no", "yes",
        "what", "when", "where", "who", "why", "how",
        "there", "here", "just", "very", "too", "also",
    )
}
