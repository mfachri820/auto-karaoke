package com.lyricsauto.lyrics

import com.lyricsauto.nowplaying.NowPlaying
import com.lyricsauto.nowplaying.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LyricsState {
    /** Nothing is playing. */
    data object Idle : LyricsState

    sealed interface ForTrack : LyricsState {
        val track: Track
    }

    data class Searching(override val track: Track) : ForTrack
    data class Synced(override val track: Track, val lines: List<LyricLine>) : ForTrack
    data class Plain(override val track: Track, val lines: List<String>) : ForTrack
    data class Instrumental(override val track: Track) : ForTrack
    data class NotFound(override val track: Track) : ForTrack
    data class Failed(override val track: Track, val message: String) : ForTrack
}

/** Follows [nowPlaying] and resolves lyrics for each new song (disk cache first, then LRCLIB). */
@OptIn(FlowPreview::class)
class LyricsRepository(
    nowPlaying: StateFlow<NowPlaying?>,
    private val client: LrcLibClient,
    private val cache: LyricsCache,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    private val retries = MutableStateFlow(0)
    private var forceNetwork = false

    init {
        val tracks = nowPlaying
            .map { it?.track }
            .distinctUntilChangedBy { it?.key }
            // Players often publish title, then artist/album/duration a moment later.
            .debounce(300)
        scope.launch {
            combine(tracks, retries) { track, _ -> track }.collectLatest { track -> load(track) }
        }
    }

    /** Fetch again from the network, ignoring a cached "not found". */
    fun retry() {
        forceNetwork = true
        retries.value++
    }

    private suspend fun load(track: Track?) {
        if (track == null) {
            _state.value = LyricsState.Idle
            return
        }
        _state.value = LyricsState.Searching(track)

        val skipCache = forceNetwork.also { forceNetwork = false }
        val cached = if (skipCache) null else withContext(io) { cache.get(track.key) }
        val result = cached ?: try {
            withContext(io) {
                val record = client.find(track)
                CachedLyrics(
                    synced = record?.syncedLyrics,
                    plain = record?.plainLyrics,
                    instrumental = record?.instrumental == true,
                    fetchedAtMs = System.currentTimeMillis(),
                ).also { cache.put(track.key, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = LyricsState.Failed(track, e.message ?: e.javaClass.simpleName)
            return
        }

        _state.value = toState(track, result)
    }

    private fun toState(track: Track, lyrics: CachedLyrics): LyricsState {
        val synced = lyrics.synced?.let(LrcParser::parse).orEmpty()
        if (synced.isNotEmpty()) return LyricsState.Synced(track, synced)
        val plain = lyrics.plain?.lines()?.map { it.trim() }?.dropWhile { it.isEmpty() }?.dropLastWhile { it.isEmpty() }
        if (!plain.isNullOrEmpty()) return LyricsState.Plain(track, plain)
        if (lyrics.instrumental) return LyricsState.Instrumental(track)
        return LyricsState.NotFound(track)
    }
}
