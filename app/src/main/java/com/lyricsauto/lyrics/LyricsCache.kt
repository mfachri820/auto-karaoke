package com.lyricsauto.lyrics

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class CachedLyrics(
    val synced: String? = null,
    val plain: String? = null,
    val instrumental: Boolean = false,
    val fetchedAtMs: Long = 0L,
) {
    val isMiss: Boolean get() = synced.isNullOrBlank() && plain.isNullOrBlank() && !instrumental
}

/**
 * One JSON file per song under filesDir, so lyrics keep working offline (e.g. in a car
 * with no signal) once a song has been played. Misses are cached too, but expire.
 */
class LyricsCache(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "lyrics").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    fun get(key: String, nowMs: Long = System.currentTimeMillis()): CachedLyrics? {
        val file = fileFor(key)
        if (!file.exists()) return null
        val cached = runCatching { json.decodeFromString<CachedLyrics>(file.readText()) }.getOrNull()
        if (cached == null) {
            file.delete()
            return null
        }
        if (cached.isMiss && nowMs - cached.fetchedAtMs > MISS_TTL_MS) return null
        file.setLastModified(nowMs)
        return cached
    }

    fun put(key: String, value: CachedLyrics) {
        runCatching {
            val tmp = File(dir, fileFor(key).name + ".tmp")
            tmp.writeText(json.encodeToString(value))
            tmp.renameTo(fileFor(key))
            prune()
        }
    }

    fun remove(key: String) {
        fileFor(key).delete()
    }

    private fun prune() {
        val files = dir.listFiles()?.takeIf { it.size > MAX_ENTRIES } ?: return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_ENTRIES).forEach { it.delete() }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    private companion object {
        const val MAX_ENTRIES = 1000
        const val MISS_TTL_MS = 24L * 60 * 60 * 1000
    }
}
