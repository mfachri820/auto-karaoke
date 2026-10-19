package com.lyricsauto.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses two and three digit fractions`() {
        val lines = LrcParser.parse("[00:01.50]a\n[01:02.345]b\n[00:03]c")
        assertEquals(listOf(1_500L, 3_000L, 62_345L), lines.map { it.timeMs })
        assertEquals(listOf("a", "c", "b"), lines.map { it.text })
    }

    @Test
    fun `expands multiple timestamps on one line and sorts`() {
        val lines = LrcParser.parse("[00:10.00][00:30.00]chorus\n[00:20.00]verse")
        assertEquals(listOf(10_000L to "chorus", 20_000L to "verse", 30_000L to "chorus"),
            lines.map { it.timeMs to it.text })
    }

    @Test
    fun `applies offset tag and skips header tags`() {
        val lines = LrcParser.parse("[ar:Someone]\n[offset:+500]\n[00:02.00]hi\n[00:00.20]early")
        assertEquals(listOf(0L, 1_500L), lines.map { it.timeMs })
    }

    @Test
    fun `negative offset delays lyrics`() {
        val lines = LrcParser.parse("[offset:-250]\n[00:01.00]x")
        assertEquals(1_250L, lines.single().timeMs)
    }

    @Test
    fun `keeps bracketed text and strips word timing`() {
        val lines = LrcParser.parse("[00:05.00][Chorus]\n[00:06.00]<00:06.00>Hello <00:06.50>world")
        assertEquals(listOf("[Chorus]", "Hello world"), lines.map { it.text })
    }

    @Test
    fun `keeps empty lines as gaps and ignores junk`() {
        val lines = LrcParser.parse("not lyrics\n[00:01.00]\n[xx:yy]nope\n[00:02.00]yes")
        assertEquals(listOf("", "yes"), lines.map { it.text })
    }

    @Test
    fun `indexAt finds the current line`() {
        val lines = LrcParser.parse("[00:01.00]a\n[00:02.00]b\n[00:03.00]c")
        assertEquals(-1, LrcParser.indexAt(lines, 999))
        assertEquals(0, LrcParser.indexAt(lines, 1_000))
        assertEquals(1, LrcParser.indexAt(lines, 2_500))
        assertEquals(2, LrcParser.indexAt(lines, 60_000))
        assertEquals(-1, LrcParser.indexAt(emptyList(), 0))
    }
}
