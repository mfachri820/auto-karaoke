package com.lyricsauto.lyrics

/**
 * Streaming apps decorate titles ("Song - Remastered 2011", "Song (feat. X)") in ways
 * LRCLIB's records usually don't. These helpers produce a second, plainer query.
 */
object MetadataCleaner {

    private val DASH_SUFFIX = Regex(
        """\s+[-–—]\s+.*\b(remaster(ed)?|live|version|edit|mix|mono|stereo|acoustic|demo|single|radio)\b.*$""",
        RegexOption.IGNORE_CASE,
    )
    private val BRACKETED = Regex(
        """\s*[(\[][^)\]]*\b(feat\.?|ft\.?|with|remaster(ed)?|live|version|edit|mix|mono|stereo|acoustic|demo|bonus|deluxe|explicit|official|video|audio|lyrics?)\b[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT_SUFFIX = Regex("""\s+(feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT = Regex("""\s*(,|&|\bx\b|\bfeat\.?|\bft\.?|\bwith\b|;|/)\s*""", RegexOption.IGNORE_CASE)

    fun cleanTitle(title: String): String =
        title.replace(BRACKETED, "")
            .replace(DASH_SUFFIX, "")
            .replace(FEAT_SUFFIX, "")
            .trim()
            .ifEmpty { title.trim() }

    /** First credited artist: "A, B & C" -> "A". */
    fun primaryArtist(artist: String): String =
        artist.split(ARTIST_SPLIT).firstOrNull { it.isNotBlank() }?.trim() ?: artist.trim()
}
