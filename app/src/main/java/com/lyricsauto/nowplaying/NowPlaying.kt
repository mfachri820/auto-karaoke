package com.lyricsauto.nowplaying

import android.graphics.Bitmap

/** Identity of a song. Deliberately excludes artwork so equality is cheap and stable. */
data class Track(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
) {
    /** Stable key for caching lyrics and remembering the per-song sync offset. */
    val key: String
        get() = listOf(artist, title, album).joinToString("|") { it.trim().lowercase() }
}

/**
 * A snapshot of PlaybackState. Position keeps moving between snapshots, so read it
 * through [positionAt] instead of using [positionMs] directly.
 */
data class PlaybackSnapshot(
    val isPlaying: Boolean,
    val positionMs: Long,
    /** SystemClock.elapsedRealtime() at which [positionMs] was reported. */
    val updatedAtElapsedMs: Long,
    val speed: Float,
) {
    fun positionAt(nowElapsedMs: Long, durationMs: Long = 0L): Long {
        val pos = if (isPlaying) {
            positionMs + ((nowElapsedMs - updatedAtElapsedMs) * speed).toLong()
        } else {
            positionMs
        }
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return pos.coerceIn(0L, upper)
    }
}

data class NowPlaying(
    val packageName: String,
    val track: Track,
    val playback: PlaybackSnapshot,
    val art: Bitmap?,
) {
    fun positionAt(nowElapsedMs: Long): Long = playback.positionAt(nowElapsedMs, track.durationMs)
}
