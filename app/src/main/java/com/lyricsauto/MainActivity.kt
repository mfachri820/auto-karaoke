package com.lyricsauto

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.lyricsauto.ui.LyricsAutoTheme
import com.lyricsauto.ui.LyricsScreen
import com.lyricsauto.ui.OnboardingScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            LyricsAutoTheme { AppRoot() }
        }
    }
}

@Composable
private fun AppRoot() {
    val graph = LocalContext.current.appGraph
    var hasAccess by remember { mutableStateOf(graph.nowPlaying.hasNotificationAccess()) }

    // Re-check whenever we come back from the settings screen.
    LifecycleResumeEffect(Unit) {
        hasAccess = graph.nowPlaying.hasNotificationAccess()
        if (hasAccess) graph.nowPlaying.refresh()
        onPauseOrDispose { }
    }

    if (hasAccess) LyricsScreen(graph) else OnboardingScreen()
}
