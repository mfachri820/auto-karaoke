package com.autolyrics.lyrics

import com.autolyrics.model.LyricLine

/**
 * Karaoke-machine style countdown: during an intro or a long instrumental break,
 * show dots that disappear one per second before the singing comes back in.
 */
object Countdown {

    const val DOTS = 3

    /** Gaps shorter than this don't get a countdown (there's no time to read it). */
    private const val MIN_GAP_MS = 4_500L

    /** Two sung lines this far apart with no ♪ marker between them count as a break. */
    private const val LONG_BREAK_MS = 12_000L

    data class Result(val dots: Int, val nextIndex: Int)

    val NONE = Result(0, -1)

    fun isVocal(line: LyricLine): Boolean = line.text.isNotBlank() && line.text != "♪"

    fun compute(lines: List<LyricLine>, posMs: Long): Result {
        if (lines.isEmpty()) return NONE

        var cur = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) cur = i else break
        }

        var next = -1
        for (i in (cur + 1) until lines.size) {
            if (isVocal(lines[i])) {
                next = i
                break
            }
        }
        if (next < 0) return NONE

        val nextStart = lines[next].timeMs
        val remaining = nextStart - posMs
        if (remaining <= 0 || remaining > DOTS * 1_000L) return NONE

        var lastVocal = -1
        for (i in cur downTo 0) {
            if (isVocal(lines[i])) {
                lastVocal = i
                break
            }
        }

        val inBreak = lastVocal < 0 ||                       // intro
            cur != lastVocal ||                              // a ♪ line is playing
            nextStart - lines[lastVocal].timeMs >= LONG_BREAK_MS
        if (!inBreak) return NONE

        val gapStart = if (cur >= 0) lines[cur].timeMs else 0L
        if (nextStart - gapStart < MIN_GAP_MS) return NONE

        val dots = ((remaining + 999) / 1_000).toInt().coerceIn(1, DOTS)
        return Result(dots, next)
    }
}
