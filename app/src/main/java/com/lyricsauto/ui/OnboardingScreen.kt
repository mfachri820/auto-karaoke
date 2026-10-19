package com.lyricsauto.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsauto.nowplaying.MediaListenerService

@Composable
fun OnboardingScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Lyrics Auto", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "See what's playing",
            color = Color.White,
            fontSize = 34.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "To follow the song in Spotify (or any player), Android needs you to turn on " +
                "notification access for Lyrics Auto. It's the only way an app can see other " +
                "apps' media sessions. Notification content is never read or stored.",
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 17.sp,
            lineHeight = 25.sp,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = { openListenerSettings(context) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Grant notification access", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "Toggle greyed out or \"Restricted setting\"?",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Android 13+ blocks this for apps installed from an APK. Open App info, tap ⋮ " +
                "(top right) → \"Allow restricted settings\", then come back and try again.",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { openAppInfo(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("Open App info")
        }
    }
}

private fun openListenerSettings(context: Context) {
    val component = ComponentName(context, MediaListenerService::class.java)
    val direct = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
    } else {
        null
    }
    val fallback = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    for (intent in listOfNotNull(direct, fallback)) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // Some OEM builds lack the detail screen; try the list instead.
        }
    }
}

private fun openAppInfo(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    )
}
