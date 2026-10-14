package com.autolyrics.model

import android.graphics.Bitmap

data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
)

data class LyricWord(
    val timeMs: Long,
    val text: String
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList()
)

data class AlbumColors(
    val dominant: Int,
    val dominantDark: Int,
    val vibrant: Int,
    val textPrimary: Int,
    val textDim: Int
)

enum class LyricsStatus {
    NO_MEDIA,
    LOADING,
    FOUND,
    PLAIN_ONLY,
    NOT_FOUND,
    ERROR
}

data class LyricsState(
    val track: TrackInfo? = null,
    val lines: List<LyricLine> = emptyList(),
    val currentIndex: Int = -1,
    val currentWordIndex: Int = -1,
    val isPlaying: Boolean = false,
    val status: LyricsStatus = LyricsStatus.NO_MEDIA,
    val source: String = "",
    val offsetMs: Long = 0,
    val albumArt: Bitmap? = null,
    val albumColors: AlbumColors? = null,
    val translatedLines: List<String>? = null,
    val detectedLanguage: String? = null,
    /** Latin-letter pronunciation per line for Korean/Japanese lyrics, or null. */
    val romanizedLines: List<String>? = null,
    /** Countdown dots left before singing resumes (0 = no countdown). */
    val countdownDots: Int = 0,
    /** Line the countdown is leading into, or -1. */
    val countdownLineIndex: Int = -1
)

/** Another lyrics match the user can pick when the automatic one is wrong. */
data class LyricsCandidate(
    val id: Int,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val syncedLyrics: String?,
    val plainLyrics: String?
)
