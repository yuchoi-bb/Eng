package com.myna.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** REQUIREMENTS §F-1 — 유튜브 앱 공유로 들어오는 텍스트에서 영상 ID를 뽑는다. */
class YouTubeUrlTest {

    private val id = "dQw4w9WgXcQ"

    @Test
    fun `쇼츠 링크에서 ID를 뽑는다`() {
        assertEquals(id, YouTubeUrl.extractVideoId("https://www.youtube.com/shorts/$id"))
        assertEquals(id, YouTubeUrl.extractVideoId("https://youtube.com/shorts/$id?feature=share"))
        assertEquals(id, YouTubeUrl.extractVideoId("https://m.youtube.com/shorts/$id"))
    }

    @Test
    fun `단축 링크에서 ID를 뽑는다`() {
        assertEquals(id, YouTubeUrl.extractVideoId("https://youtu.be/$id"))
        assertEquals(id, YouTubeUrl.extractVideoId("https://youtu.be/$id?t=12"))
    }

    @Test
    fun `watch 링크에서 ID를 뽑는다`() {
        assertEquals(id, YouTubeUrl.extractVideoId("https://www.youtube.com/watch?v=$id"))
        assertEquals(id, YouTubeUrl.extractVideoId("https://www.youtube.com/watch?v=$id&t=10s"))
        assertEquals(id, YouTubeUrl.extractVideoId("https://www.youtube.com/watch?list=PL123&v=$id"))
    }

    @Test
    fun `embed와 live 링크도 받는다`() {
        assertEquals(id, YouTubeUrl.extractVideoId("https://www.youtube.com/embed/$id"))
        assertEquals(id, YouTubeUrl.extractVideoId("https://www.youtube.com/live/$id"))
    }

    @Test
    fun `공유 문구에 섞인 URL을 찾아낸다`() {
        // 유튜브 앱의 공유는 제목과 안내 문구를 함께 보내는 경우가 흔하다.
        val shared = "Daily English Phrases\nhttps://youtube.com/shorts/$id?si=abc\n\n이 영상 보기"
        assertEquals(id, YouTubeUrl.extractVideoId(shared))
    }

    @Test
    fun `스킴이 없어도 인식한다`() {
        assertEquals(id, YouTubeUrl.extractVideoId("youtu.be/$id"))
        assertEquals(id, YouTubeUrl.extractVideoId("www.youtube.com/shorts/$id"))
    }

    @Test
    fun `유튜브가 아닌 호스트는 거부한다`() {
        // 접미사 검사를 하면 myyoutube.com 같은 호스트가 통과한다.
        assertNull(YouTubeUrl.extractVideoId("https://myyoutube.com/shorts/$id"))
        assertNull(YouTubeUrl.extractVideoId("https://vimeo.com/watch?v=$id"))
        assertNull(YouTubeUrl.extractVideoId("https://youtube.com.evil.example/shorts/$id"))
    }

    @Test
    fun `ID 길이가 맞지 않으면 거부한다`() {
        assertNull(YouTubeUrl.extractVideoId("https://youtu.be/tooshort"))
        assertNull(YouTubeUrl.extractVideoId("https://www.youtube.com/watch?v=waaaaaaaaytoolong"))
    }

    @Test
    fun `유튜브지만 영상이 아닌 링크는 거부한다`() {
        assertNull(YouTubeUrl.extractVideoId("https://www.youtube.com/"))
        assertNull(YouTubeUrl.extractVideoId("https://www.youtube.com/feed/subscriptions"))
    }

    @Test
    fun `빈 입력을 견딘다`() {
        assertNull(YouTubeUrl.extractVideoId(null))
        assertNull(YouTubeUrl.extractVideoId(""))
        assertNull(YouTubeUrl.extractVideoId("   "))
        assertNull(YouTubeUrl.extractVideoId("그냥 평범한 한국어 문장입니다"))
    }

    @Test
    fun `isYouTubeLink는 추출 가능 여부와 같다`() {
        assertTrue(YouTubeUrl.isYouTubeLink("https://youtu.be/$id"))
        assertFalse(YouTubeUrl.isYouTubeLink("https://example.com"))
    }
}
