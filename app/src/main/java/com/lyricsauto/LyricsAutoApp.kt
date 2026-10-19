package com.lyricsauto

import android.app.Application
import android.content.Context
import com.lyricsauto.lyrics.LrcLibClient
import com.lyricsauto.lyrics.LyricsCache
import com.lyricsauto.lyrics.LyricsRepository
import com.lyricsauto.lyrics.OffsetStore
import com.lyricsauto.nowplaying.NowPlayingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LyricsAutoApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** Process-wide singletons, shared by the phone UI and the Android Auto screen. */
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val nowPlaying = NowPlayingRepository(context)
    val offsets = OffsetStore(context)
    val lyrics = LyricsRepository(nowPlaying.nowPlaying, LrcLibClient(), LyricsCache(context), scope)
}

val Context.appGraph: AppGraph get() = (applicationContext as LyricsAutoApp).graph
