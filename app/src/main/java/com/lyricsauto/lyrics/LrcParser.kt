package com.lyricsauto.lyrics

data class LyricLine(val timeMs: Long, val text: String)

object LrcParser {

    // [mm:ss], [mm:ss.x], [mm:ss.xx], [mm:ss.xxx]; some files use ':' before the fraction.
    private val TIME_TAG = Regex("""^(\d+):(\d{1,2})(?:[.:](\d{1,3}))?$""")
    private val OFFSET_TAG = Regex("""^offset:\s*([+-]?\d+)$""", RegexOption.IGNORE_CASE)
    // Word-level timing from "enhanced" LRC, e.g. <00:12.34>; we only show whole lines.
    private val WORD_TAG = Regex("""<\d+:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val SPACES = Regex("""\s+""")

    /**
     * Parses LRC text into lines sorted by time. A line with several timestamps
     * (`[00:10.00][00:42.50]Chorus`) becomes one entry per timestamp, and an
     * `[offset:+/-ms]` tag is applied to every line (positive = show lyrics earlier).
     */
    fun parse(lrc: String): List<LyricLine> {
        var offsetMs = 0L
        val entries = mutableListOf<Pair<Long, String>>()

        for (rawLine in lrc.lineSequence()) {
            var rest = rawLine.trim()
            val times = mutableListOf<Long>()

            while (rest.startsWith("[")) {
                val end = rest.indexOf(']')
                if (end < 0) break
                val tag = rest.substring(1, end).trim()
                val time = TIME_TAG.matchEntire(tag)
                if (time != null) {
                    times += toMillis(time)
                } else if (times.isEmpty()) {
                    // Header tag such as [ar:..] or [offset:..]; nothing else on this line counts.
                    OFFSET_TAG.matchEntire(tag)?.let { offsetMs = it.groupValues[1].toLong() }
                    rest = ""
                    break
                } else {
                    // Text that itself starts with a bracket, e.g. "[00:12.00][Chorus]".
                    break
                }
                rest = rest.substring(end + 1)
            }

            if (times.isEmpty()) continue
            val text = rest.replace(WORD_TAG, "").replace(SPACES, " ").trim()
            times.forEach { entries += it to text }
        }

        return entries
            .map { (time, text) -> LyricLine((time - offsetMs).coerceAtLeast(0L), text) }
            .sortedBy { it.timeMs }
    }

    /** Index of the line being sung at [positionMs], or -1 before the first line. */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun toMillis(match: MatchResult): Long {
        val (minutes, seconds, fraction) = match.destructured
        val fractionMs = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.toLong()
        }
        return minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs
    }
}
