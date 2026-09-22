package com.myna.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TRANSCRIPTION_SCHEMA §3.2 / V-5 / V-6. 여기가 틀리면 L2 채점이 오답을 만든다. */
class KeywordsTest {

    @Test
    fun `대소문자와 구두점을 무시한다`() {
        val text = Keywords.normalize("What are you UP TO this weekend?!")
        assertTrue(Keywords.appearsIn(text, "up to"))
        assertTrue(Keywords.appearsIn(text, "Weekend"))
    }

    @Test
    fun `부분 단어 일치를 걸러낸다`() {
        // 단순 contains를 쓰면 "up"이 "upset"에 걸린다.
        val text = Keywords.normalize("She was upset about the update")
        assertFalse(Keywords.appearsIn(text, "up"))
        assertTrue(Keywords.appearsIn(text, "upset"))
    }

    @Test
    fun `여러 단어로 된 표현도 찾는다`() {
        val text = Keywords.normalize("I'm going to look after the dog")
        assertTrue(Keywords.appearsIn(text, "look after"))
        assertFalse(Keywords.appearsIn(text, "after look"))
    }

    @Test
    fun `아포스트로피는 단어의 일부로 본다`() {
        val text = Keywords.normalize("I don't know")
        assertTrue(Keywords.appearsIn(text, "don't"))
    }

    @Test
    fun `내용어 대체는 기능어를 건너뛴다`() {
        // §3.2 — 관사·전치사 단독 금지.
        assertEquals(
            listOf("tomorrow", "leaving"),
            Keywords.topContentWords("I am leaving for the office tomorrow", limit = 2),
        )
    }

    @Test
    fun `내용어 대체는 결정적이다`() {
        val text = "Coffee please"
        assertEquals(
            Keywords.topContentWords(text, limit = 2),
            Keywords.topContentWords(text, limit = 2),
        )
    }

    @Test
    fun `기능어만 있는 문장에서도 키워드를 하나는 만든다`() {
        // 0개가 되면 L2가 채점 기준을 잃고, firestore.rules의 keywords.size() > 0에도 걸린다.
        val keywords = Keywords.topContentWords("It is the one", limit = 2)
        assertTrue(keywords.isNotEmpty())
    }

    @Test
    fun `빈 문자열은 빈 목록을 돌려준다`() {
        assertTrue(Keywords.topContentWords("   ", limit = 2).isEmpty())
    }
}
