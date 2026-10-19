package com.lyricsauto.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyricsauto.AppGraph
import com.lyricsauto.lyrics.LrcParser
import com.lyricsauto.lyrics.LyricLine
import com.lyricsauto.lyrics.LyricsState
import com.lyricsauto.nowplaying.NowPlaying
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlin.math.abs
import kotlin.math.max

private const val OFFSET_STEP_MS = 500L
private const val POSITION_TICK_MS = 32L
private const val USER_SCROLL_HOLD_MS = 3_000L

@Composable
fun LyricsScreen(graph: AppGraph) {
    val nowPlaying by graph.nowPlaying.nowPlaying.collectAsStateWithLifecycle()
    val lyricsState by graph.lyrics.state.collectAsStateWithLifecycle()
    val np = nowPlaying
    val trackKey = np?.track?.key

    val offsetFlow = remember(trackKey) { trackKey?.let(graph.offsets::observe) ?: flowOf(0L) }
    val offsetMs by offsetFlow.collectAsStateWithLifecycle(initialValue = trackKey?.let(graph.offsets::get) ?: 0L)

    KeepScreenOn()

    Box(Modifier.fillMaxSize().background(Ink)) {
        AlbumBackdrop(np?.art)
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (np != null) TrackHeader(np)

            val state = lyricsState
            val current = state is LyricsState.ForTrack && np != null && state.track.key == np.track.key
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    np == null -> Message(
                        title = "Nothing playing",
                        body = "Start a song in Spotify and its lyrics will show up here.",
                    )
                    !current || state is LyricsState.Searching -> Searching()
                    state is LyricsState.Synced -> key(np.track.key) {
                        SyncedLyrics(state.lines, np, offsetMs, onSeek = graph.nowPlaying::seekTo)
                    }
                    state is LyricsState.Plain -> PlainLyrics(state.lines)
                    state is LyricsState.Instrumental -> Message("Instrumental", "No words in this one. Enjoy the music.")
                    state is LyricsState.NotFound -> Message(
                        title = "No lyrics found",
                        body = "LRCLIB doesn't have lyrics for this song yet.",
                        action = "Search again",
                        onAction = graph.lyrics::retry,
                    )
                    state is LyricsState.Failed -> Message(
                        title = "Couldn't load lyrics",
                        body = "Check your connection. (${state.message})",
                        action = "Retry",
                        onAction = graph.lyrics::retry,
                    )
                }
            }

            if (np != null && current && state is LyricsState.Synced) {
                OffsetBar(
                    offsetMs = offsetMs,
                    onAdjust = { graph.offsets.adjust(np.track.key, it) },
                    onReset = { graph.offsets.set(np.track.key, 0L) },
                )
            }
        }
    }
}

@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    nowPlaying: NowPlaying,
    offsetMs: Long,
    onSeek: (Long) -> Unit,
) {
    val position = rememberPlaybackPosition(nowPlaying)
    // Positive offset delays lyrics, so look up the line for an earlier moment in the song.
    val currentIndex by remember(lines, offsetMs) {
        derivedStateOf { LrcParser.indexAt(lines, position.value - offsetMs) }
    }

    val listState = rememberLazyListState()
    var dragging by remember { mutableStateOf(false) }
    var lastUserScrollAt by remember { mutableLongStateOf(0L) }
    var centeredOnce by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> dragging = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    dragging = false
                    lastUserScrollAt = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    // Keep the current line centred, but leave the list alone for a moment after the user scrolls it.
    LaunchedEffect(currentIndex, dragging, lastUserScrollAt) {
        if (dragging) return@LaunchedEffect
        val holdFor = lastUserScrollAt + USER_SCROLL_HOLD_MS - SystemClock.elapsedRealtime()
        if (holdFor > 0) delay(holdFor)
        listState.centerOn(currentIndex.coerceAtLeast(0), animate = centeredOnce)
        centeredOnce = true
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = maxHeight / 2),
        ) {
            itemsIndexed(lines) { index, line ->
                LyricLineRow(
                    text = line.text,
                    distance = index - currentIndex,
                    onClick = { onSeek(line.timeMs + offsetMs) },
                )
            }
        }
    }
}

@Composable
private fun LyricLineRow(text: String, distance: Int, onClick: () -> Unit) {
    val active = distance == 0
    val alpha by animateFloatAsState(
        targetValue = when {
            active -> 1f
            distance < 0 -> 0.28f
            abs(distance) == 1 -> 0.55f
            else -> 0.4f
        },
        animationSpec = tween(350),
        label = "lineAlpha",
    )
    val scale by animateFloatAsState(if (active) 1f else 0.9f, tween(350, easing = FastOutSlowInEasing), label = "lineScale")
    Text(
        text = text.ifBlank { "♪" },
        color = Color.White,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
    )
}

@Composable
private fun PlainLyrics(lines: List<String>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 20.dp),
    ) {
        item {
            Text(
                "Not synced — showing plain lyrics",
                color = Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Accent.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
        items(lines) { line ->
            Text(
                text = line.ifEmpty { " " },
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 22.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun TrackHeader(np: NowPlaying) {
    val art = remember(np.art) { np.art?.asImageBitmap() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val artModifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
        if (art != null) {
            Image(art, contentDescription = null, modifier = artModifier, contentScale = ContentScale.Crop)
        } else {
            Box(artModifier.background(Color.White.copy(alpha = 0.08f)))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                np.track.title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                np.track.artist,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!np.playback.isPlaying) {
            Text("Paused", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun OffsetBar(offsetMs: Long, onAdjust: (Long) -> Unit, onReset: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = { onAdjust(-OFFSET_STEP_MS) }) {
            StepLabel("−", "Show lyrics 0.5 seconds earlier")
        }
        TextButton(onClick = onReset, modifier = Modifier.width(150.dp)) {
            Text(
                text = when {
                    offsetMs == 0L -> "Sync ±0.0s"
                    offsetMs > 0 -> "%.1fs later".format(offsetMs / 1000.0)
                    else -> "%.1fs earlier".format(-offsetMs / 1000.0)
                },
                color = Color.White.copy(alpha = if (offsetMs == 0L) 0.6f else 1f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
        }
        FilledTonalIconButton(onClick = { onAdjust(OFFSET_STEP_MS) }) {
            StepLabel("+", "Show lyrics 0.5 seconds later")
        }
    }
}

@Composable
private fun StepLabel(symbol: String, description: String) {
    Text(
        symbol,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun Searching() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Accent)
        Spacer(Modifier.height(16.dp))
        Text("Searching lyrics…", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
    }
}

@Composable
private fun Message(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            body,
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 16.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun AlbumBackdrop(art: Bitmap?) {
    val source = remember(art) { art?.let(::tinyCopy) }
    Crossfade(targetState = source, animationSpec = tween(600), label = "backdrop") { image ->
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.15f; scaleY = 1.15f }
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(40.dp) else Modifier),
            )
        }
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.55f),
                0.5f to Color.Black.copy(alpha = 0.68f),
                1f to Color.Black.copy(alpha = 0.88f),
            )
        )
    )
}

/**
 * A ~24px copy of the cover. Stretched back to full screen with bilinear filtering it already
 * looks blurred on every API level; on Android 12+ Modifier.blur smooths it further.
 */
private fun tinyCopy(bitmap: Bitmap): ImageBitmap? = runCatching {
    if (bitmap.isRecycled) return null
    val width = 24
    val height = max(1, width * bitmap.height / max(1, bitmap.width))
    Bitmap.createScaledBitmap(bitmap, width, height, true).asImageBitmap()
}.getOrNull()

/** Song position that keeps advancing between PlaybackState updates. */
@Composable
private fun rememberPlaybackPosition(nowPlaying: NowPlaying): State<Long> =
    produceState(nowPlaying.positionAt(SystemClock.elapsedRealtime()), nowPlaying.playback, nowPlaying.track) {
        value = nowPlaying.positionAt(SystemClock.elapsedRealtime())
        while (nowPlaying.playback.isPlaying) {
            delay(POSITION_TICK_MS)
            value = nowPlaying.positionAt(SystemClock.elapsedRealtime())
        }
    }

private suspend fun LazyListState.centerOn(index: Int, animate: Boolean) {
    if (layoutInfo.totalItemsCount == 0) return
    val visible = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (visible == null) {
        // Far away (first show, or a seek): jump there, wait for layout, then fine-tune.
        scrollToItem(index)
        withFrameNanos { }
        withFrameNanos { }
        val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        scrollBy(centerDelta(item.offset, item.size, layoutInfo))
        return
    }
    val delta = centerDelta(visible.offset, visible.size, layoutInfo)
    if (animate) {
        animateScrollBy(delta, tween(durationMillis = 450, easing = FastOutSlowInEasing))
    } else {
        scrollBy(delta)
    }
}

private fun centerDelta(offset: Int, size: Int, info: LazyListLayoutInfo): Float {
    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return offset + size / 2f - viewportCenter
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
