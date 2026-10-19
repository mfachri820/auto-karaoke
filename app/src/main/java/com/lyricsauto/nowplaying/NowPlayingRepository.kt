package com.lyricsauto.nowplaying

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the media session the user most likely cares about (Spotify first) and exposes it
 * as a [StateFlow]. Shared by the phone UI and, later, the Android Auto screen.
 *
 * All methods must be called on the main thread.
 */
class NowPlayingRepository(context: Context) {

    private val appContext = context.applicationContext
    private val sessionManager = appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(appContext, MediaListenerService::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val callbacks = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var active: MediaController? = null

    fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(appContext).contains(appContext.packageName)

    /** Re-reads the active sessions. A no-op until notification access is granted. */
    fun refresh() {
        val controllers = try {
            sessionManager.getActiveSessions(listenerComponent)
        } catch (_: SecurityException) {
            return
        }
        onSessionsChanged(controllers)
    }

    fun onSessionsChanged(controllers: List<MediaController>?) {
        val current = controllers.orEmpty().filter { it.packageName != appContext.packageName }
        val tokens = current.map { it.sessionToken }.toSet()

        callbacks.keys.filter { it !in tokens }.forEach { token ->
            callbacks.remove(token)?.let { (controller, callback) -> controller.unregisterCallback(callback) }
        }
        for (controller in current) {
            if (controller.sessionToken in callbacks) continue
            val callback = ControllerCallback()
            controller.registerCallback(callback, mainHandler)
            callbacks[controller.sessionToken] = controller to callback
        }
        reevaluate()
    }

    fun seekTo(positionMs: Long) {
        active?.transportControls?.seekTo(positionMs.coerceAtLeast(0L))
    }

    private fun reevaluate() {
        val controllers = callbacks.values.map { it.first }
        val chosen = pick(controllers)
        active = chosen
        _nowPlaying.value = chosen?.let(::snapshot)
    }

    /** Playing Spotify > whatever we already show, if still playing > anything playing > Spotify > anything. */
    private fun pick(controllers: List<MediaController>): MediaController? {
        val playing = controllers.filter { it.playbackState?.isActive() == true && it.hasTitle() }
        val withTitle = controllers.filter { it.hasTitle() }
        val current = active?.sessionToken
        return playing.firstOrNull { it.packageName == SPOTIFY }
            ?: playing.firstOrNull { it.sessionToken == current }
            ?: playing.firstOrNull()
            ?: withTitle.firstOrNull { it.packageName == SPOTIFY }
            ?: withTitle.firstOrNull { it.sessionToken == current }
            ?: withTitle.firstOrNull()
    }

    private fun snapshot(controller: MediaController): NowPlaying? {
        val metadata = controller.metadata ?: return null
        val title = metadata.text(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return null
        val artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)

        val state = controller.playbackState
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val playback = if (state != null) {
            PlaybackSnapshot(
                isPlaying = isPlaying,
                positionMs = state.position,
                updatedAtElapsedMs = state.lastPositionUpdateTime.takeIf { it > 0 }
                    ?: SystemClock.elapsedRealtime(),
                speed = state.playbackSpeed.takeIf { it > 0f } ?: 1f,
            )
        } else {
            PlaybackSnapshot(false, 0L, SystemClock.elapsedRealtime(), 1f)
        }

        val track = Track(title, artist, album, duration)
        // Every metadata read hands us a fresh Bitmap; reuse the old one so the UI
        // doesn't redo its blur work on each play/pause/seek.
        val previous = _nowPlaying.value
        val art = previous?.art?.takeIf {
            previous.packageName == controller.packageName && previous.track == track && !it.isRecycled
        } ?: metadata.art()

        return NowPlaying(
            packageName = controller.packageName,
            track = track,
            playback = playback,
            art = art,
        )
    }

    private inner class ControllerCallback : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = reevaluate()
        override fun onPlaybackStateChanged(state: PlaybackState?) = reevaluate()
        override fun onSessionDestroyed() = refresh()
    }

    private companion object {
        const val SPOTIFY = "com.spotify.music"

        fun PlaybackState.isActive(): Boolean = state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_CONNECTING

        fun MediaController.hasTitle(): Boolean =
            metadata?.text(MediaMetadata.METADATA_KEY_TITLE) != null ||
                metadata?.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) != null

        fun MediaMetadata.text(key: String): String? =
            getString(key)?.trim()?.takeIf { it.isNotEmpty() }

        fun MediaMetadata.art(): Bitmap? =
            (getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
                ?.takeUnless { it.isRecycled }
    }
}
