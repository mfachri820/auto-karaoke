package com.autolyrics

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autolyrics.lyrics.Countdown
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import kotlinx.coroutines.launch

class PerformanceActivity : AppCompatActivity() {

    private lateinit var mediaTracker: MediaTracker
    private lateinit var root: FrameLayout
    private lateinit var albumBg: ImageView
    private lateinit var albumArt: ImageView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var sourceLabel: TextView
    private lateinit var prevLine: TextView
    private lateinit var currentLine: TextView
    private lateinit var currentRoman: TextView
    private lateinit var nextLine: TextView
    private lateinit var exitHint: TextView

    private var lastRenderedIndex = -1
    private var lastRenderedWord = -1
    private var lastRenderedCountdown = -1
    private var wordPopAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_performance)

        enterImmersiveMode()

        mediaTracker = MediaTracker.getInstance(this)

        root = findViewById(R.id.perf_root)
        albumBg = findViewById(R.id.perf_album_bg)
        albumArt = findViewById(R.id.perf_album_art)
        trackTitle = findViewById(R.id.perf_track_title)
        trackArtist = findViewById(R.id.perf_track_artist)
        sourceLabel = findViewById(R.id.perf_source)
        prevLine = findViewById(R.id.perf_prev_line)
        currentLine = findViewById(R.id.perf_current_line)
        currentRoman = findViewById(R.id.perf_current_roman)
        nextLine = findViewById(R.id.perf_next_line)
        exitHint = findViewById(R.id.perf_exit_hint)

        albumArt.clipToOutline = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Soft glow of the album art behind the lyrics (Android 12+).
            albumBg.setRenderEffect(RenderEffect.createBlurEffect(90f, 90f, Shader.TileMode.CLAMP))
            albumBg.alpha = 0.6f
        }

        root.setOnClickListener { finish() }

        exitHint.animate().alpha(0f).setStartDelay(3000).setDuration(1000).start()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mediaTracker.state.collect { state -> render(state) }
            }
        }
    }

    override fun onDestroy() {
        wordPopAnimator?.cancel()
        wordPopAnimator = null
        super.onDestroy()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).let { ctrl ->
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun render(state: LyricsState) {
        state.track?.let { track ->
            trackTitle.text = track.title
            trackArtist.text = track.artist.ifBlank { "" }
        }

        state.albumArt?.let { art ->
            albumBg.setImageBitmap(art)
            albumArt.setImageBitmap(art)
            albumArt.visibility = View.VISIBLE
        } ?: run {
            albumBg.setImageDrawable(null)
            albumArt.visibility = View.GONE
        }

        if (state.source.isNotBlank()) {
            sourceLabel.text = state.source.substringBefore(" · ").trim()
            sourceLabel.visibility = View.VISIBLE
        } else {
            sourceLabel.visibility = View.GONE
        }

        val colors = state.albumColors
        val accentColor = colors?.vibrant ?: DEFAULT_ACCENT

        if (state.status != LyricsStatus.FOUND) {
            // Make sure synced lyrics redraw fully when they (re)appear.
            lastRenderedIndex = -1
            lastRenderedWord = -1
            lastRenderedCountdown = -1
            if (state.status != LyricsStatus.PLAIN_ONLY) setRoman(null)
        }

        when (state.status) {
            LyricsStatus.NO_MEDIA -> {
                prevLine.text = ""
                currentLine.text = "Play a song"
                nextLine.text = ""
            }
            LyricsStatus.LOADING -> {
                prevLine.text = ""
                currentLine.text = "Finding lyrics…"
                nextLine.text = ""
            }
            LyricsStatus.NOT_FOUND -> {
                prevLine.text = ""
                currentLine.text = "No lyrics found"
                nextLine.text = ""
            }
            LyricsStatus.ERROR -> {
                prevLine.text = ""
                currentLine.text = "Couldn't load lyrics"
                nextLine.text = ""
            }
            LyricsStatus.PLAIN_ONLY -> {
                renderPlain(state)
            }
            LyricsStatus.FOUND -> {
                renderSynced(state, accentColor)
            }
        }
    }

    private fun renderPlain(state: LyricsState) {
        val lines = state.lines
        if (lines.isEmpty()) {
            prevLine.text = ""
            currentLine.text = "♪"
            nextLine.text = ""
            return
        }

        val durationMs = state.track?.durationMs ?: 0
        val posMs = try {
            mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
        } catch (_: Exception) { 0L }

        val idx = if (durationMs > 0) {
            ((posMs.toFloat() / durationMs) * lines.size).toInt()
                .coerceIn(0, lines.size - 1)
        } else 0

        updateSideLine(prevLine, sideText(state, idx - 1))
        currentLine.text = lines[idx].text.ifBlank { "♪" }
        setRoman(state.romanizedLines?.getOrNull(idx))
        updateSideLine(nextLine, sideText(state, idx + 1))
    }

    private fun renderSynced(state: LyricsState, accentColor: Int) {
        val idx = state.currentIndex
        val wordIdx = state.currentWordIndex
        val lines = state.lines

        // Countdown dots before singing comes back in.
        if (state.countdownDots > 0 && state.countdownLineIndex in lines.indices) {
            val key = state.countdownDots * 10_000 + state.countdownLineIndex
            if (key != lastRenderedCountdown) {
                lastRenderedCountdown = key
                wordPopAnimator?.cancel()
                currentLine.text = countdownText(state.countdownDots, accentColor)
                setRoman(null)
                val justSung = lines.getOrNull(idx)?.takeIf { Countdown.isVocal(it) }
                updateSideLine(prevLine, if (justSung != null) sideText(state, idx) else null)
                updateSideLine(nextLine, sideText(state, state.countdownLineIndex))
                // Force a full redraw once the countdown ends.
                lastRenderedIndex = -2
                lastRenderedWord = -2
            }
            return
        }
        lastRenderedCountdown = -1

        if (idx < 0 || idx >= lines.size) {
            // Intro: show what's coming up first.
            wordPopAnimator?.cancel()
            prevLine.text = ""
            currentLine.text = "♪"
            setRoman(null)
            val firstVocal = lines.indexOfFirst { Countdown.isVocal(it) }
            nextLine.text = sideText(state, firstVocal) ?: ""
            lastRenderedIndex = -1
            lastRenderedWord = -1
            return
        }

        val lineChanged = idx != lastRenderedIndex
        val wordChanged = wordIdx != lastRenderedWord
        if (!lineChanged && !wordChanged) return

        lastRenderedIndex = idx
        lastRenderedWord = wordIdx

        if (lineChanged) {
            updateSideLine(prevLine, sideText(state, idx - 1))
            updateSideLine(nextLine, sideText(state, idx + 1))
            setRoman(state.romanizedLines?.getOrNull(idx))
        }

        val line = lines[idx]
        val hasKaraoke = line.words.isNotEmpty()

        if (hasKaraoke && wordIdx >= 0) {
            wordPopAnimator?.cancel()

            val baseSizePx = currentLine.textSize.toInt()

            wordPopAnimator = ValueAnimator.ofFloat(1.0f, 1.12f).apply {
                duration = 200
                interpolator = OvershootInterpolator(1.4f)
                addUpdateListener { anim ->
                    val scale = anim.animatedValue as Float
                    val sizePx = (baseSizePx * scale).toInt()
                    currentLine.text = buildKaraokeSpannable(
                        line, wordIdx, sizePx, accentColor
                    )
                }
                start()
            }
        } else {
            wordPopAnimator?.cancel()
            currentLine.text = line.text.ifBlank { "♪" }
        }
    }

    private fun buildKaraokeSpannable(
        line: LyricLine,
        activeWordIdx: Int,
        activeSizePx: Int,
        accentColor: Int
    ): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        line.words.forEachIndexed { wi, word ->
            val start = ssb.length
            ssb.append(word.text)
            val end = ssb.length

            // Karaoke fill: sung words take the accent color, the word being sung pops.
            if (wi <= activeWordIdx) {
                ssb.setSpan(ForegroundColorSpan(accentColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (wi == activeWordIdx) {
                ssb.setSpan(AbsoluteSizeSpan(activeSizePx), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (wi < line.words.size - 1) ssb.append(" ")
        }
        return ssb
    }

    /** Text for the lines above/below: the pronunciation when there is one, since that's what gets sung. */
    private fun sideText(state: LyricsState, index: Int): String? {
        val line = state.lines.getOrNull(index) ?: return null
        val roman = state.romanizedLines?.getOrNull(index)
        return if (!roman.isNullOrBlank()) roman else line.text
    }

    private fun setRoman(text: String?) {
        if (text.isNullOrBlank()) {
            currentRoman.text = ""
            currentRoman.visibility = View.GONE
        } else {
            currentRoman.text = text
            currentRoman.visibility = View.VISIBLE
        }
    }

    private fun countdownText(dots: Int, accentColor: Int): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        for (k in 1..Countdown.DOTS) {
            val start = ssb.length
            ssb.append("●")
            val color = if (k <= dots) accentColor else setAlpha(accentColor, 0.22f)
            ssb.setSpan(ForegroundColorSpan(color), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (k < Countdown.DOTS) ssb.append("   ")
        }
        return ssb
    }

    private fun updateSideLine(tv: TextView, text: String?) {
        val newText = text ?: ""
        if (tv.text.toString() == newText) return
        tv.animate().alpha(0f).setDuration(100).withEndAction {
            tv.text = newText
            tv.animate().alpha(1f).setDuration(180).start()
        }.start()
    }

    companion object {
        private val DEFAULT_ACCENT = Color.parseColor("#FFB547")

        private fun setAlpha(color: Int, alpha: Float): Int {
            val a = (alpha * 255).toInt().coerceIn(0, 255)
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }
    }
}
