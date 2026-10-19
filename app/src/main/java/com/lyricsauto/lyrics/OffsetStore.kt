package com.lyricsauto.lyrics

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Per-song sync correction in milliseconds, keyed by [com.lyricsauto.nowplaying.Track.key].
 * Positive values delay the lyrics (use when they run ahead of the singer).
 */
class OffsetStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("sync_offsets", Context.MODE_PRIVATE)
    private val version = MutableStateFlow(0)

    fun get(trackKey: String): Long = prefs.getLong(trackKey, 0L)

    fun observe(trackKey: String): Flow<Long> = version.map { get(trackKey) }.distinctUntilChanged()

    fun set(trackKey: String, offsetMs: Long) {
        prefs.edit().apply {
            if (offsetMs == 0L) remove(trackKey) else putLong(trackKey, offsetMs)
        }.apply()
        version.value++
    }

    fun adjust(trackKey: String, deltaMs: Long) = set(trackKey, get(trackKey) + deltaMs)
}
