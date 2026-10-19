package com.lyricsauto.nowplaying

import android.content.ComponentName
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import com.lyricsauto.appGraph

/**
 * We don't read notifications. Being an enabled listener is simply the permission Android
 * requires before it lets an app see other apps' media sessions.
 */
class MediaListenerService : NotificationListenerService() {

    private val repository get() = appGraph.nowPlaying
    private var sessionManager: MediaSessionManager? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        repository.onSessionsChanged(controllers)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val manager = getSystemService(MediaSessionManager::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(
                sessionsListener,
                ComponentName(this, MediaListenerService::class.java),
            )
            sessionManager = manager
        } catch (_: SecurityException) {
            return
        }
        repository.refresh()
    }

    override fun onListenerDisconnected() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        sessionManager = null
        super.onListenerDisconnected()
    }
}
