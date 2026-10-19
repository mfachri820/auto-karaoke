package com.lyricsauto.lyrics

import com.lyricsauto.BuildConfig
import com.lyricsauto.nowplaying.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

@Serializable
data class LrcLibRecord(
    val id: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

/** Client for https://lrclib.net (free, no API key). Blocking; call from Dispatchers.IO. */
class LrcLibClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val baseUrl: HttpUrl = "https://lrclib.net/api/".toHttpUrl(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Best record for [track]: exact `/get` first (raw, then cleaned metadata), then `/search`
     * ranked by duration. A synced record is preferred over a plain-only one.
     * Returns null when LRCLIB has nothing; throws [IOException] on network/server errors.
     */
    fun find(track: Track): LrcLibRecord? {
        val durationSec = (track.durationMs / 1000.0).roundToInt()
        val title = MetadataCleaner.cleanTitle(track.title)
        val artist = MetadataCleaner.primaryArtist(track.artist)

        var best: LrcLibRecord? = null
        fun consider(record: LrcLibRecord?) {
            if (record == null || !record.hasContent()) return
            if (best == null || (best!!.syncedLyrics == null && record.syncedLyrics != null)) best = record
        }

        if (durationSec > 0) {
            consider(get(track.title, track.artist, track.album, durationSec))
            if (best?.syncedLyrics == null && (title != track.title || artist != track.artist)) {
                consider(get(title, artist, track.album, durationSec))
            }
        }
        if (best?.syncedLyrics != null || best?.instrumental == true) return best

        val queries = listOf("$title $artist", title).distinct()
        for (q in queries) {
            consider(pickBest(search(q), durationSec))
            if (best?.syncedLyrics != null) break
        }
        return best
    }

    private fun get(title: String, artist: String, album: String, durationSec: Int): LrcLibRecord? {
        val url = baseUrl.newBuilder().addPathSegment("get")
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("track_name", title)
            .addQueryParameter("album_name", album)
            .addQueryParameter("duration", durationSec.toString())
            .build()
        return request(url)?.let { json.decodeFromString<LrcLibRecord>(it) }
    }

    private fun search(query: String): List<LrcLibRecord> {
        val url = baseUrl.newBuilder().addPathSegment("search")
            .addQueryParameter("q", query)
            .build()
        return request(url)?.let { json.decodeFromString<List<LrcLibRecord>>(it) }.orEmpty()
    }

    /** Body on 2xx, null on 404, [IOException] otherwise. */
    private fun request(url: HttpUrl): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("LRCLIB returned HTTP ${response.code}")
            return response.body?.string()
        }
    }

    companion object {
        val USER_AGENT = "LyricsAuto/${BuildConfig.VERSION_NAME} (https://github.com/mfachri820/auto-karaoke)"

        /** Search results further than this from the playing track's length are a different recording. */
        private const val DURATION_TOLERANCE_SEC = 8.0

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        internal fun pickBest(results: List<LrcLibRecord>, durationSec: Int): LrcLibRecord? {
            val candidates = results.filter { it.hasContent() }.filter {
                durationSec <= 0 || it.duration == null || abs(it.duration - durationSec) <= DURATION_TOLERANCE_SEC
            }
            return candidates.minWithOrNull(
                compareBy<LrcLibRecord> { if (it.syncedLyrics != null) 0 else 1 }
                    .thenBy { if (durationSec > 0 && it.duration != null) abs(it.duration - durationSec) else 0.0 }
            )
        }

        private fun LrcLibRecord.hasContent(): Boolean =
            instrumental || !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank()
    }
}
