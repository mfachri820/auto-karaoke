package com.lyricsauto.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatchingTest {

    private fun record(id: Long, duration: Double?, synced: Boolean, plain: Boolean = true) = LrcLibRecord(
        id = id,
        duration = duration,
        syncedLyrics = if (synced) "[00:01.00]x" else null,
        plainLyrics = if (plain) "x" else null,
    )

    @Test
    fun `prefers synced within tolerance, then closest duration`() {
        val results = listOf(
            record(1, 200.0, synced = false),
            record(2, 230.0, synced = true),
            record(3, 204.0, synced = true),
            record(4, 201.0, synced = true),
        )
        assertEquals(4L, LrcLibClient.pickBest(results, 200)?.id)
    }

    @Test
    fun `falls back to plain when no synced record fits`() {
        val results = listOf(record(1, 300.0, synced = true), record(2, 199.0, synced = false))
        assertEquals(2L, LrcLibClient.pickBest(results, 200)?.id)
    }

    @Test
    fun `ignores empty records`() {
        assertNull(LrcLibClient.pickBest(listOf(record(1, 200.0, synced = false, plain = false)), 200))
    }

    @Test
    fun `cleans streaming decorations`() {
        assertEquals("Yesterday", MetadataCleaner.cleanTitle("Yesterday - Remastered 2009"))
        assertEquals("Song", MetadataCleaner.cleanTitle("Song (feat. Someone)"))
        assertEquals("Song", MetadataCleaner.cleanTitle("Song feat. Someone"))
        assertEquals("Love (Me)", MetadataCleaner.cleanTitle("Love (Me)"))
        assertEquals("A", MetadataCleaner.primaryArtist("A, B & C"))
    }
}
