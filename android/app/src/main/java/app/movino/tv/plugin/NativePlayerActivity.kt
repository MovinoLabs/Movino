package app.movino.tv.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.PaintDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Full-screen ExoPlayer activity for Android TV with:
 * - Cinematic loading backdrop (poster/background with blur + title pulse)
 * - Seek bar with D-pad left/right control
 * - On-screen subtitle (CC), audio (🔊), and speed (⚡) buttons in bottom bar
 * - Buffering indicator overlay
 * - Polished track selector panel with rounded corners and smooth styling
 * - Auto track selection based on user language preferences
 * - Resume position support
 * - Progress sync back to web app via broadcast
 * - Playback speed control (0.5x - 2.0x)
 * - Optimized buffering (fast start, large forward buffer, back-buffer)
 * - Segment caching for instant seek-back
 * - Surround sound passthrough (Dolby Atmos, DTS-HD, TrueHD)
 * - HDR + tunneled rendering support
 */
class NativePlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PAYLOAD = "native_player_payload"
        const val ACTION_CLOSE = "app.movino.tv.CLOSE_PLAYER"
        const val ACTION_PROGRESS = "app.movino.tv.PLAYER_PROGRESS"
        const val ACTION_CLOSED = "app.movino.tv.PLAYER_CLOSED"
        private const val OVERLAY_HIDE_DELAY = 5000L
        private const val PLAYBACK_BADGE_HIDE_DELAY = 900L
        private const val PROGRESS_SYNC_INTERVAL = 5000L
        private const val SEEK_STEP_MS = 10_000L

        // Accent color (warm gold to match Movino brand)
        private const val ACCENT = "#D4A84B"
        private const val ACCENT_DIM = "#99D4A84B"
        private const val PANEL_BG = "#F0121218"
        private const val SURFACE_ELEVATED = "#2A2A2E"
        private const val TEXT_PRIMARY = "#EDE8D5"
        private const val TEXT_SECONDARY = "#99AAAAAA"
        private const val SEEK_TRACK_IDLE = "#3C2D0F"
        private const val SEEK_TRACK_FOCUSED = "#7A6230"
        private const val SEEK_PROGRESS_IDLE = "#8F6E28"
        private const val SEEK_PROGRESS_FOCUSED = "#D4A84B"
        private const val SEEK_THUMB_IDLE = "#B2872C"
        private const val SEEK_THUMB_FOCUSED = "#FFF4D0"

        val SPEED_OPTIONS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

        const val MAX_AUDIO_FALLBACK_ATTEMPTS = 5

        // Shared segment cache (100 MB, survives activity restarts)
        private var simpleCache: SimpleCache? = null
        private val cacheLock = Any()

        fun getCache(context: Context): SimpleCache {
            synchronized(cacheLock) {
                if (simpleCache == null) {
                    val cacheDir = java.io.File(context.cacheDir, "exo_media_cache")
                    val evictor = LeastRecentlyUsedCacheEvictor(100L * 1024 * 1024) // 100 MB
                    simpleCache = SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(context))
                }
                return simpleCache!!
            }
        }
    }

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var playerView: PlayerView? = null
    private var titleOverlay: LinearLayout? = null
    private var trackSelectorOverlay: FrameLayout? = null
    private var trackSelectorPanel: LinearLayout? = null
    private var loadingBackdrop: FrameLayout? = null
    private var bottomBar: LinearLayout? = null
    private var seekBarLayout: LinearLayout? = null
    private var seekBar: SeekBar? = null
    private var timeCurrentText: TextView? = null
    private var timeDurationText: TextView? = null
    private var bufferingOverlay: FrameLayout? = null
    private var playbackBadge: TextView? = null
    private var speedButton: TextView? = null
    private var subtitleButton: TextView? = null
    private var audioButton: TextView? = null
    private val controlButtons = mutableListOf<TextView>()

    private var preferredAudioLangs: List<String> = emptyList()
    private var preferredSubtitleLangs: List<String> = emptyList()
    private var hasAppliedPreferences = false
    private var resumePositionMs: Long = 0L
    private var hasResumed = false
    private var mediaId: String = ""
    private var mediaType: String = "movie"
    private var currentSpeedIndex: Int = 2 // index of 1.0f in SPEED_OPTIONS
    private var isClosing = false
    private var closeEventSent = false
    private var audioFallbackAttempts = 0
    private var sourceRetryAttempts = 0
    private val MAX_SOURCE_RETRIES = 3
    private var tunnelingDisabledForFallback = false
    private var softwareDecoderRetried = false
    private var excludedAudioMimeTypes = mutableSetOf<String>()
    private var lastSelectedMediaSource: androidx.media3.exoplayer.source.MediaSource? = null
    private var lastWorkingAudioParams: androidx.media3.common.TrackSelectionParameters? = null
    private var lastWorkingAudioPosition: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideControlOverlays() }
    private val hidePlaybackBadgeRunnable = Runnable {
        playbackBadge?.animate()?.alpha(0f)?.setDuration(160)?.withEndAction {
            playbackBadge?.visibility = View.GONE
        }?.start()
    }

    private var currentTrackMenu: TrackMenuType = TrackMenuType.NONE

    private enum class TrackMenuType { NONE, AUDIO, SUBTITLE, SPEED }

    // Seek acceleration
    private var seekRepeatCount = 0
    private var isSeeking = false
    private var pendingSeekPosition: Long = -1L

    private fun finalizeSeekSession(commitPending: Boolean) {
        if (commitPending) {
            val pos = pendingSeekPosition
            if (pos >= 0) {
                player?.seekTo(pos)
            }
        }
        pendingSeekPosition = -1L
        seekRepeatCount = 0
        isSeeking = false
    }

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            requestCloseToDetail()
        }
    }

    // Progress sync runnable
    private val progressSyncRunnable = object : Runnable {
        override fun run() {
            syncProgressToWebApp()
            handler.postDelayed(this, PROGRESS_SYNC_INTERVAL)
        }
    }

    // Seek bar update runnable
    private val seekBarUpdateRunnable = object : Runnable {
        override fun run() {
            updateSeekBar()
            handler.postDelayed(this, 1000)
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register the onBackPressedDispatcher callback — this catches ALL back
        // gestures and predictive back on Android 13+, not just KEYCODE_BACK.
        // This ensures a single Back press always closes the player.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // If track menu is open, close it instead of exiting the player
                if (currentTrackMenu != TrackMenuType.NONE) {
                    dismissTrackMenu()
                    return
                }
                // If error overlay is showing, close the player
                requestCloseToDetail()
            }
        })

        // Full-screen immersive
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        )

        // Parse payload
        val payloadStr = intent.getStringExtra(EXTRA_PAYLOAD) ?: run {
            finish()
            return
        }

        val payload = try { JSONObject(payloadStr) } catch (e: Exception) { finish(); return }

        val sourceUrl = payload.optString("sourceUrl", "")
        val sourceType = payload.optString("sourceType", "progressive")
        val title = payload.optString("title", "")
        val streamTitle = payload.optString("streamTitle", "")
        val background = payload.optString("background", "")
        val poster = payload.optString("poster", "")
        val backdropUrl = background.ifEmpty { poster }
        mediaId = payload.optString("mediaId", "")
        mediaType = payload.optString("type", "movie")
        resumePositionMs = (payload.optDouble("resumePosition", 0.0) * 1000).toLong()

        if (sourceUrl.isEmpty()) { finish(); return }

        // Parse language preferences
        preferredAudioLangs = jsonArrayToList(payload.optJSONArray("preferredAudioLangs"))
        preferredSubtitleLangs = jsonArrayToList(payload.optJSONArray("preferredSubtitleLangs"))

        // Register close broadcast
        registerReceiver(closeReceiver, IntentFilter(ACTION_CLOSE), RECEIVER_NOT_EXPORTED)

        // Build root layout
        val rootLayout = FrameLayout(this)
        rootLayout.setBackgroundColor(Color.BLACK)

        // Build player with surround passthrough + HDR + tunneled rendering
        val renderersFactory = DefaultRenderersFactory(this).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableAudioTrackPlaybackParams(true)
        }

        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage(preferredAudioLangs.firstOrNull() ?: "und")
                    .setPreferredTextLanguage(
                        if (preferredSubtitleLangs.isNotEmpty()) preferredSubtitleLangs.first() else "off"
                    )
                    .setRendererDisabled(C.TRACK_TYPE_TEXT, preferredSubtitleLangs.isEmpty())
                    .setAllowVideoNonSeamlessAdaptiveness(true)
                    .setPreferredAudioMimeTypes(
                        MimeTypes.AUDIO_E_AC3_JOC,  // Dolby Atmos
                        MimeTypes.AUDIO_E_AC3,       // Dolby Digital Plus
                        MimeTypes.AUDIO_AC3,         // Dolby Digital
                        MimeTypes.AUDIO_DTS,         // DTS
                        MimeTypes.AUDIO_DTS_HD,      // DTS-HD
                        MimeTypes.AUDIO_TRUEHD,      // Dolby TrueHD
                        MimeTypes.AUDIO_AAC          // AAC fallback
                    )
                    .setTunnelingEnabled(true)
            )
        }

        // Optimized buffer strategy for TV: fast start + large forward buffer
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 15_000,
                /* maxBufferMs */ 180_000,
                /* bufferForPlaybackMs */ 1_000,
                /* bufferForPlaybackAfterRebufferMs */ 3_000
            )
            .setBackBuffer(/* backBufferDurationMs */ 60_000, /* retainBackBufferFromKeyframe */ true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .build()
            .also { exo ->
                exo.setSeekParameters(SeekParameters.CLOSEST_SYNC)

                playerView = PlayerView(this).apply {
                    player = exo
                    useController = false
                    isFocusable = true
                    isFocusableInTouchMode = true
                    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                }
                rootLayout.addView(playerView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))

                // Center playback feedback badge — Movino gold accent circle
                val badgeBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#CC0E0E12"))
                    setStroke(dp(3), Color.parseColor(ACCENT))
                }
                playbackBadge = TextView(this).apply {
                    text = "❚❚"
                    setTextColor(Color.parseColor(ACCENT))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                    gravity = Gravity.CENTER
                    this.background = badgeBg
                    visibility = View.GONE
                    alpha = 0f
                    isFocusable = false
                    isFocusableInTouchMode = false
                    elevation = dp(10).toFloat()
                    setShadowLayer(24f, 0f, 0f, Color.parseColor("#66D4A84B"))
                }
                rootLayout.addView(playbackBadge, FrameLayout.LayoutParams(
                    dp(88), dp(88), Gravity.CENTER
                ))

                // ── Cinematic loading backdrop ──
                loadingBackdrop = FrameLayout(this).apply {
                    setBackgroundColor(Color.BLACK)
                }

                val backdropImage = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    alpha = 0.35f
                    scaleX = 1.15f
                    scaleY = 1.15f
                }
                loadingBackdrop!!.addView(backdropImage, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))

                val gradientOverlay = View(this)
                gradientOverlay.background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.BLACK, Color.parseColor("#66000000"), Color.parseColor("#33000000"))
                )
                loadingBackdrop!!.addView(gradientOverlay, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))

                val loadingContent = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                }

                val loadingTitle = TextView(this).apply {
                    text = title
                    setTextColor(Color.parseColor(TEXT_PRIMARY))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setShadowLayer(40f, 0f, 2f, Color.parseColor("#66C89B30"))
                    maxLines = 2
                }
                loadingContent.addView(loadingTitle)

                // Pulse animation on the loading title
                loadingTitle.animate()
                    .alpha(0.5f)
                    .setDuration(1200)
                    .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction(object : Runnable {
                        var fadingIn = true
                        override fun run() {
                            if (loadingBackdrop?.visibility == View.GONE) return
                            val target = if (fadingIn) 1f else 0.5f
                            fadingIn = !fadingIn
                            loadingTitle.animate()
                                .alpha(target)
                                .setDuration(1200)
                                .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                                .withEndAction(this)
                                .start()
                        }
                    })
                    .start()

                if (streamTitle.isNotEmpty()) {
                    val loadingSubtitle = TextView(this).apply {
                        text = streamTitle
                        setTextColor(Color.parseColor(TEXT_SECONDARY))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        gravity = Gravity.CENTER
                        setPadding(0, dp(8), 0, 0)
                    }
                    loadingContent.addView(loadingSubtitle)
                }

                // Shimmer loading bar with sweep animation
                val shimmerTrack = FrameLayout(this)
                val shimmerTrackBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#1AFFFFFF"))
                    cornerRadius = dp(2).toFloat()
                }
                shimmerTrack.background = shimmerTrackBg
                val shimmerParams = LinearLayout.LayoutParams(dp(160), dp(3)).apply {
                    gravity = Gravity.CENTER
                    topMargin = dp(24)
                }
                loadingContent.addView(shimmerTrack, shimmerParams)

                // Animated shimmer highlight
                val shimmerHighlight = View(this).apply {
                    val grad = GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(Color.TRANSPARENT, Color.parseColor("#55D4A84B"), Color.TRANSPARENT)
                    )
                    grad.cornerRadius = dp(2).toFloat()
                    this.background = grad
                }
                shimmerTrack.addView(shimmerHighlight, FrameLayout.LayoutParams(dp(64), dp(3)))

                // Animate shimmer sweep
                shimmerHighlight.post {
                    val totalWidth = dp(160).toFloat()
                    shimmerHighlight.translationX = -dp(64).toFloat()
                    val anim = android.animation.ObjectAnimator.ofFloat(
                        shimmerHighlight, "translationX", -dp(64).toFloat(), totalWidth
                    ).apply {
                        duration = 1400
                        repeatCount = android.animation.ValueAnimator.INFINITE
                        repeatMode = android.animation.ValueAnimator.RESTART
                        interpolator = android.view.animation.LinearInterpolator()
                    }
                    anim.start()
                }

                loadingBackdrop!!.addView(loadingContent, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                ))

                rootLayout.addView(loadingBackdrop, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))

                // Load backdrop image async with blur
                if (backdropUrl.isNotEmpty()) {
                    thread {
                        try {
                            val bitmap = URL(backdropUrl).openStream().use { BitmapFactory.decodeStream(it) }
                            handler.post {
                                backdropImage.setImageBitmap(bitmap)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    backdropImage.setRenderEffect(
                                        android.graphics.RenderEffect.createBlurEffect(
                                            40f, 40f,
                                            android.graphics.Shader.TileMode.CLAMP
                                        )
                                    )
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                // ── Buffering overlay (spinner) ──
                bufferingOverlay = FrameLayout(this).apply {
                    visibility = View.GONE
                    setBackgroundColor(Color.parseColor("#66000000"))
                }
                val spinner = ProgressBar(this).apply {
                    isIndeterminate = true
                }
                bufferingOverlay!!.addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
                rootLayout.addView(bufferingOverlay, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))

                // ── Top title overlay ──
                val titleOverlayLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(48), dp(40), dp(48), 0)
                    visibility = View.GONE
                }

                val topGradient = View(this)
                topGradient.background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.parseColor("#AA000000"), Color.TRANSPARENT)
                )
                rootLayout.addView(topGradient, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, dp(160), Gravity.TOP
                ))

                val titleText = TextView(this).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    typeface = Typeface.DEFAULT_BOLD
                    setShadowLayer(8f, 2f, 2f, Color.BLACK)
                    maxLines = 1
                }
                titleOverlayLayout.addView(titleText)

                if (streamTitle.isNotEmpty()) {
                    val streamTitleText = TextView(this).apply {
                        text = streamTitle
                        setTextColor(Color.parseColor("#B0FFFFFF"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setPadding(0, dp(4), 0, 0)
                        maxLines = 1
                    }
                    titleOverlayLayout.addView(streamTitleText)
                }

                titleOverlay = titleOverlayLayout
                rootLayout.addView(titleOverlayLayout, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                ))

                // ── Seek bar ──
                val seekLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(48), 0, dp(48), dp(8))
                    visibility = View.GONE
                }

                timeCurrentText = TextView(this).apply {
                    text = "0:00"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.MONOSPACE
                }
                seekLayout.addView(timeCurrentText)

                val sb = SeekBar(this).apply {
                    id = View.generateViewId()
                    max = 1000
                    progress = 0
                    // Prevent SeekBar's own D-pad stepping — we handle seek entirely
                    // in handleSeek() via the OnKeyListener below. Setting this to 0
                    // stops the native "second thumb" movement that conflicts with ours.
                    keyProgressIncrement = 0
                    isFocusable = true
                    isFocusableInTouchMode = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    // Make thumb larger for TV visibility
                    thumb?.let { t ->
                        t.setBounds(0, 0, dp(20), dp(20))
                    }
                    // Intercept D-pad seek on the focused seek bar itself. On some Android TV
                    // devices, SeekBar consumes LEFT/RIGHT before Activity.onKeyDown runs.
                    setOnKeyListener { _, keyCode, keyEvent ->
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                            when (keyEvent.action) {
                                KeyEvent.ACTION_DOWN -> {
                                    handleSeek(forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT, event = keyEvent)
                                    true
                                }
                                KeyEvent.ACTION_UP -> {
                                    if (isSeeking || pendingSeekPosition >= 0) {
                                        finalizeSeekSession(commitPending = true)
                                    }
                                    true
                                }
                                else -> true
                            }
                        } else {
                            false
                        }
                    }
                    // Visual focus feedback
                    setOnFocusChangeListener { v, hasFocus ->
                        val scale = if (hasFocus) 1.45f else 1.0f
                        v.animate().scaleY(scale).setDuration(180).start()
                        applySeekBarFocusStyle(hasFocus)
                    }
                }
                val sbParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(12)
                    rightMargin = dp(12)
                }
                seekLayout.addView(sb, sbParams)
                seekBar = sb
                applySeekBarFocusStyle(false)

                timeDurationText = TextView(this).apply {
                    text = "0:00"
                    setTextColor(Color.parseColor("#AAFFFFFF"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.MONOSPACE
                }
                seekLayout.addView(timeDurationText)

                seekBarLayout = seekLayout
                rootLayout.addView(seekLayout, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                ).apply { bottomMargin = dp(76) })

                // ── Bottom control bar with Subtitles, Audio, and Speed buttons ──
                val bottomGradient = View(this)
                bottomGradient.background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.parseColor("#DD000000"), Color.parseColor("#66000000"), Color.TRANSPARENT)
                )
                rootLayout.addView(bottomGradient, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, dp(200), Gravity.BOTTOM
                ))

                val bar = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
                    setPadding(dp(48), dp(16), dp(48), dp(24))
                    visibility = View.GONE
                }

                subtitleButton = createControlButton("⬙ Subtitles") {
                    showTrackMenu(TrackMenuType.SUBTITLE)
                }
                bar.addView(subtitleButton)

                audioButton = createControlButton("🔊 Audio") {
                    showTrackMenu(TrackMenuType.AUDIO)
                }
                bar.addView(audioButton, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(20) })

                speedButton = createControlButton("⚡ 1.0x") {
                    showTrackMenu(TrackMenuType.SPEED)
                }
                bar.addView(speedButton, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(20) })

                controlButtons.clear()
                subtitleButton?.let(controlButtons::add)
                audioButton?.let(controlButtons::add)
                speedButton?.let(controlButtons::add)

                bottomBar = bar
                rootLayout.addView(bar, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                ))

                // ── Track selector overlay (slide-in panel) ──
                trackSelectorOverlay = FrameLayout(this).apply {
                    visibility = View.GONE
                    setOnClickListener { dismissTrackMenu() }
                }

                val panelBackdrop = View(this).apply {
                    setBackgroundColor(Color.parseColor("#80000000"))
                }
                trackSelectorOverlay!!.addView(panelBackdrop, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))

                trackSelectorPanel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    isFocusable = false
                    isFocusableInTouchMode = false
                }

                val trackScrollView = android.widget.ScrollView(this).apply {
                    isVerticalScrollBarEnabled = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    // Smooth scrolling when focus moves to off-screen items
                    isSmoothScrollingEnabled = true
                    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    addView(trackSelectorPanel, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                }

                val panelContainer = FrameLayout(this).apply {
                    val panelBg = GradientDrawable().apply {
                        setColor(Color.parseColor(PANEL_BG))
                        cornerRadii = floatArrayOf(
                            dp(16).toFloat(), dp(16).toFloat(),
                            0f, 0f,
                            0f, 0f,
                            dp(16).toFloat(), dp(16).toFloat()
                        )
                    }
                    setBackground(panelBg)
                    setPadding(dp(32), dp(32), dp(32), dp(32))
                    elevation = dp(8).toFloat()
                    addView(trackScrollView, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                }

                trackSelectorOverlay!!.addView(panelContainer, FrameLayout.LayoutParams(
                    dp(380), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END
                ))

                rootLayout.addView(trackSelectorOverlay, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))

                setContentView(rootLayout)
                playerView?.post { playerView?.requestFocus() }

                // Build media source with cached data source for segment reuse
                // Use Stremio-compatible User-Agent to avoid debrid server blocks
                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Stremio/5.0.0")
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(30_000)
                    .setReadTimeoutMs(30_000)

                val behaviorHints = payload.optJSONObject("behaviorHints")
                val proxyHeaders = behaviorHints?.optJSONObject("proxyHeaders")
                val requestHeaders = proxyHeaders?.optJSONObject("request")
                if (requestHeaders != null) {
                    val headers = mutableMapOf<String, String>()
                    requestHeaders.keys().forEach { key ->
                        headers[key] = requestHeaders.optString(key, "")
                    }
                    if (headers.isNotEmpty()) {
                        httpDataSourceFactory.setDefaultRequestProperties(headers)
                    }
                }

                // Wrap with cache for segment reuse on seek-back
                val cachedFactory = CacheDataSource.Factory()
                    .setCache(getCache(this@NativePlayerActivity))
                    .setUpstreamDataSourceFactory(httpDataSourceFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

                // Build subtitle configurations
                val subtitleConfigs = mutableListOf<SubtitleConfiguration>()
                val subtitlesArray = payload.optJSONArray("subtitles")
                if (subtitlesArray != null) {
                    for (i in 0 until subtitlesArray.length()) {
                        val sub = subtitlesArray.optJSONObject(i) ?: continue
                        val subUrl = sub.optString("url", "")
                        val subLang = sub.optString("lang", "und")
                        val subLabel = sub.optString("label", "").ifEmpty { subLang.uppercase() }
                        val subId = sub.optString("id", "sub_$i")
                        if (subUrl.isNotEmpty()) {
                            val mimeType = guessMimeType(subUrl)
                            subtitleConfigs.add(
                                SubtitleConfiguration.Builder(Uri.parse(subUrl))
                                    .setMimeType(mimeType)
                                    .setLanguage(subLang)
                                    .setLabel(subLabel)
                                    .setId(subId)
                                    .build()
                            )
                        }
                    }
                }

                val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(sourceUrl))
                if (subtitleConfigs.isNotEmpty()) {
                    mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
                }
                val mediaItem = mediaItemBuilder.build()

                val mediaSource: MediaSource = when {
                    sourceType == "hls" || sourceUrl.contains(".m3u8", ignoreCase = true) ->
                        HlsMediaSource.Factory(cachedFactory)
                            .createMediaSource(mediaItem)
                    sourceType == "dash" || sourceUrl.contains(".mpd", ignoreCase = true) ->
                        DashMediaSource.Factory(cachedFactory)
                            .createMediaSource(mediaItem)
                    sourceUrl.contains(".ism", ignoreCase = true) || sourceUrl.contains("Manifest", ignoreCase = true) ->
                        SsMediaSource.Factory(cachedFactory)
                            .createMediaSource(mediaItem)
                    sourceUrl.startsWith("rtsp://", ignoreCase = true) ->
                        RtspMediaSource.Factory()
                            .createMediaSource(mediaItem)
                    else -> ProgressiveMediaSource.Factory(cachedFactory)
                        .createMediaSource(mediaItem)
                }

                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        handler.post {
                            if (isRecoverableAudioError(exo, error) && audioFallbackAttempts < MAX_AUDIO_FALLBACK_ATTEMPTS) {
                                handleRecoverableAudioError(exo, error)
                            } else if (isSourceError(error) && sourceRetryAttempts < MAX_SOURCE_RETRIES) {
                                // Auto-retry source errors (expired URLs, network drops, segment failures)
                                sourceRetryAttempts++
                                val retryPosition = exo.currentPosition
                                bufferingOverlay?.visibility = View.VISIBLE
                                handler.postDelayed({
                                    try {
                                        exo.prepare()
                                        if (retryPosition > 0) exo.seekTo(retryPosition)
                                        exo.play()
                                    } catch (_: Exception) {
                                        showErrorOverlay("Playback failed")
                                    }
                                }, 1500L * sourceRetryAttempts) // Increasing delay: 1.5s, 3s, 4.5s
                            } else {
                                val errorMsg = error.localizedMessage ?: ""
                                bufferingOverlay?.visibility = View.GONE
                                loadingBackdrop?.visibility = View.GONE
                                showErrorOverlay(errorMsg.ifEmpty { "Playback failed" })
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                sourceRetryAttempts = 0 // Reset on successful playback
                                loadingBackdrop?.animate()
                                    ?.alpha(0f)
                                    ?.setDuration(600)
                                    ?.withEndAction {
                                        loadingBackdrop?.visibility = View.GONE
                                    }?.start()
                                bufferingOverlay?.visibility = View.GONE
                                showControlOverlays()

                                // Resume position (only once)
                                if (!hasResumed && resumePositionMs > 0) {
                                    hasResumed = true
                                    exo.seekTo(resumePositionMs)
                                }

                                // Start progress sync
                                handler.removeCallbacks(progressSyncRunnable)
                                handler.postDelayed(progressSyncRunnable, PROGRESS_SYNC_INTERVAL)

                                // Start seek bar updates
                                handler.removeCallbacks(seekBarUpdateRunnable)
                                handler.post(seekBarUpdateRunnable)
                            }
                            Player.STATE_BUFFERING -> {
                                if (loadingBackdrop?.visibility == View.GONE) {
                                    bufferingOverlay?.visibility = View.VISIBLE
                                }
                            }
                            Player.STATE_ENDED -> {
                                syncProgressToWebApp(completed = true)
                                finish()
                            }
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        if (!hasAppliedPreferences) {
                            hasAppliedPreferences = true
                            applyPreferredTracks(exo)
                        }
                        // Proactively check if selected audio codec is supported
                        proactiveAudioCodecCheck(exo)
                    }
                })

                lastSelectedMediaSource = mediaSource
                exo.setMediaSource(mediaSource)
                exo.prepare()
                exo.playWhenReady = true
            }
    }

    // ── Seek bar ───────────────────────────────────────────────────────

    private fun updateSeekBar() {
        val exo = player ?: return
        val duration = exo.duration
        val position = exo.currentPosition
        if (duration > 0) {
            seekBar?.progress = ((position * 1000) / duration).toInt()
            timeCurrentText?.text = formatTime(position)
            timeDurationText?.text = formatTime(duration)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    // ── Progress sync ──────────────────────────────────────────────────

    private fun syncProgressToWebApp(completed: Boolean = false) {
        val exo = player ?: return
        val intent = Intent(ACTION_PROGRESS).apply {
            setPackage(packageName)
            putExtra("mediaId", mediaId)
            putExtra("type", mediaType)
            putExtra("currentTime", exo.currentPosition / 1000.0)
            putExtra("duration", exo.duration / 1000.0)
            putExtra("completed", completed)
        }
        sendBroadcast(intent)
    }

    private fun sendClosedEventOnce() {
        if (closeEventSent) return
        closeEventSent = true
        sendBroadcast(Intent(ACTION_CLOSED).apply {
            setPackage(packageName)
            putExtra("mediaId", mediaId)
            putExtra("type", mediaType)
        })
    }

    private fun requestCloseToDetail() {
        if (isClosing) return
        isClosing = true

        handler.removeCallbacks(hideOverlayRunnable)
        handler.removeCallbacks(progressSyncRunnable)
        handler.removeCallbacks(seekBarUpdateRunnable)
        handler.removeCallbacks(hidePlaybackBadgeRunnable)

        syncProgressToWebApp()
        sendClosedEventOnce()

        player?.playWhenReady = false
        finish()
        overridePendingTransition(0, 0)
    }

    // ── Control button factory ─────────────────────────────────────────

    private fun createControlButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
            isFocusable = true
            isFocusableInTouchMode = true
            minWidth = dp(100)

            val bg = PaintDrawable(Color.parseColor("#33FFFFFF"))
            bg.setCornerRadius(dp(24).toFloat())
            this.background = bg

            setOnFocusChangeListener { _, hasFocus ->
                val bgColor = if (hasFocus) Color.parseColor(ACCENT) else Color.parseColor("#33FFFFFF")
                (background as? PaintDrawable)?.paint?.color = bgColor
                setTextColor(if (hasFocus) Color.BLACK else Color.WHITE)
                // Subtle scale animation
                val scale = if (hasFocus) 1.08f else 1.0f
                animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            }

            setOnClickListener { onClick() }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_UP) {
                    onClick()
                    true
                } else false
            }
        }
    }

    // ── Preferred track auto-selection ─────────────────────────────────

    @OptIn(UnstableApi::class)
    private fun applyPreferredTracks(exo: ExoPlayer) {
        val tracks = exo.currentTracks

        if (preferredAudioLangs.isNotEmpty()) {
            for (prefLang in preferredAudioLangs) {
                val audioGroup = tracks.groups.firstOrNull { group ->
                    group.type == C.TRACK_TYPE_AUDIO &&
                    (0 until group.length).any { i ->
                        group.getTrackFormat(i).language?.lowercase()?.startsWith(prefLang.lowercase().take(3)) == true
                    }
                }
                if (audioGroup != null) {
                    val trackIndex = (0 until audioGroup.length).firstOrNull { i ->
                        audioGroup.getTrackFormat(i).language?.lowercase()?.startsWith(prefLang.lowercase().take(3)) == true
                    } ?: 0
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(audioGroup.mediaTrackGroup, listOf(trackIndex)))
                        .build()
                    break
                }
            }
        }

        if (preferredSubtitleLangs.isNotEmpty()) {
            for (prefLang in preferredSubtitleLangs) {
                val subGroup = tracks.groups.firstOrNull { group ->
                    group.type == C.TRACK_TYPE_TEXT &&
                    (0 until group.length).any { i ->
                        group.getTrackFormat(i).language?.lowercase()?.startsWith(prefLang.lowercase().take(3)) == true
                    }
                }
                if (subGroup != null) {
                    val trackIndex = (0 until subGroup.length).firstOrNull { i ->
                        subGroup.getTrackFormat(i).language?.lowercase()?.startsWith(prefLang.lowercase().take(3)) == true
                    } ?: 0
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(subGroup.mediaTrackGroup, listOf(trackIndex)))
                        .build()
                    break
                }
            }
        }
    }

    // ── Track selector UI ──────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    private fun showTrackMenu(type: TrackMenuType) {
        val exo = player ?: return
        val panel = trackSelectorPanel ?: return
        val overlay = trackSelectorOverlay ?: return

        // Ensure we don't carry stale pending-seek state into menu navigation.
        finalizeSeekSession(commitPending = true)

        currentTrackMenu = type
        panel.removeAllViews()

        if (type == TrackMenuType.SPEED) {
            showSpeedMenu(panel, overlay, exo)
            return
        }

        // Header with icon
        val header = TextView(this).apply {
            text = if (type == TrackMenuType.AUDIO) "🔊  Audio" else "💬  Subtitles"
            setTextColor(Color.parseColor(ACCENT))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, dp(20))
        }
        panel.addView(header)

        // Divider
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }
        panel.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { bottomMargin = dp(12) })

        val tracks = exo.currentTracks
        val trackType = if (type == TrackMenuType.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT

        var preferredFocusItem: View? = null
        var firstFocusableItem: View? = null

        // "Off" option for subtitles
        if (type == TrackMenuType.SUBTITLE) {
            val isDisabled = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            val offItem = createTrackMenuItem("Off", isDisabled) {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                dismissTrackMenu()
            }
            panel.addView(offItem)
            if (firstFocusableItem == null) firstFocusableItem = offItem
            if (isDisabled) preferredFocusItem = offItem
        }

        // List available tracks
        for (group in tracks.groups) {
            if (group.type != trackType) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val lang = format.language
                val label = formatSubtitleLabel(format.label, lang)
                val isSelected = group.isTrackSelected(i)

                // All tracks are selectable — no ⚠ marking.
                // If a track fails, the player will auto-retry with tunneling off,
                // then software decode, then fallback to a safe track.
                val displayLabel = label

                val item = createTrackMenuItem(displayLabel, isSelected) {
                    if (type == TrackMenuType.SUBTITLE) {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(i)))
                            .build()
                    } else {
                        // Audio: full prepare() to cleanly reset decoder
                        // Save current working state so we can revert on failure
                        lastWorkingAudioParams = exo.trackSelectionParameters
                        lastWorkingAudioPosition = exo.currentPosition
                        switchAudioTrackWithPrepare(exo, group, i)
                    }
                    dismissTrackMenu()
                }

                panel.addView(item)
                if (firstFocusableItem == null) firstFocusableItem = item
                if (isSelected && preferredFocusItem == null) preferredFocusItem = item
            }
        }

        // Animate panel in from right
        overlay.visibility = View.VISIBLE
        panel.translationX = dp(380).toFloat()
        panel.animate().translationX(0f).setDuration(250).start()
        panel.post { (preferredFocusItem ?: firstFocusableItem ?: panel).requestFocus() }

        handler.removeCallbacks(hideOverlayRunnable)
    }

    private fun showSpeedMenu(panel: LinearLayout, overlay: FrameLayout, exo: ExoPlayer) {
        val header = TextView(this).apply {
            text = "⚡  Speed"
            setTextColor(Color.parseColor(ACCENT))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, dp(20))
        }
        panel.addView(header)

        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }
        panel.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { bottomMargin = dp(12) })

        var selectedItem: View? = null
        var firstItem: View? = null

        for ((index, speed) in SPEED_OPTIONS.withIndex()) {
            val label = if (speed == 1.0f) "Normal (1.0x)" else "${speed}x"
            val isSelected = index == currentSpeedIndex
            val item = createTrackMenuItem(label, isSelected) {
                currentSpeedIndex = index
                // Disable tunneling when speed != 1.0x (hardware tunneled decoders
                // don't support variable playback speed on most Android TV devices)
                if (speed != 1.0f) {
                    trackSelector?.setParameters(
                        trackSelector!!.buildUponParameters()
                            .setTunnelingEnabled(false)
                            .build()
                    )
                } else {
                    trackSelector?.setParameters(
                        trackSelector!!.buildUponParameters()
                            .setTunnelingEnabled(true)
                            .build()
                    )
                }
                exo.playbackParameters = PlaybackParameters(speed)
                speedButton?.text = if (speed == 1.0f) "⚡ 1.0x" else "⚡ ${speed}x"
                dismissTrackMenu()
            }
            panel.addView(item)
            if (firstItem == null) firstItem = item
            if (isSelected) selectedItem = item
        }

        overlay.visibility = View.VISIBLE
        panel.translationX = dp(380).toFloat()
        panel.animate().translationX(0f).setDuration(250).start()
        panel.post { (selectedItem ?: firstItem ?: panel).requestFocus() }

        handler.removeCallbacks(hideOverlayRunnable)
    }

    private fun createTrackMenuItem(label: String, isSelected: Boolean, onClick: () -> Unit): View {
        val parts = label.split("\n", limit = 2)
        val hasSubLabel = parts.size == 2

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            isFocusable = true
            isFocusableInTouchMode = true

            val itemBg = PaintDrawable(if (isSelected) Color.parseColor("#1AD4A84B") else Color.TRANSPARENT)
            itemBg.setCornerRadius(dp(12).toFloat())
            this.background = itemBg

            setOnFocusChangeListener { _, hasFocus ->
                val bgColor = if (hasFocus) Color.parseColor("#44FFFFFF") else if (isSelected) Color.parseColor("#1AD4A84B") else Color.TRANSPARENT
                (background as? PaintDrawable)?.paint?.color = bgColor
                val scale = if (hasFocus) 1.03f else 1.0f
                animate().scaleX(scale).scaleY(scale).setDuration(120).start()
                invalidate()
            }

            setOnClickListener { onClick() }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_UP) {
                    onClick()
                    true
                } else false
            }
        }

        // Main label (language name)
        val mainText = TextView(this).apply {
            text = if (isSelected) "✓  ${parts[0]}" else parts[0]
            setTextColor(if (isSelected) Color.parseColor(ACCENT) else Color.parseColor("#DDDDDD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = if (isSelected || hasSubLabel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            maxLines = 1
        }
        container.addView(mainText)

        // Sub label (source name like "LostFilm", "NewStudio")
        if (hasSubLabel) {
            val subText = TextView(this).apply {
                text = parts[1]
                setTextColor(Color.parseColor(TEXT_SECONDARY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                setPadding(if (isSelected) dp(28) else 0, dp(2), 0, 0)
            }
            container.addView(subText)
        }

        return container
    }

    private fun dismissTrackMenu() {
        val panel = trackSelectorPanel
        val overlay = trackSelectorOverlay
        if (panel != null && overlay != null) {
            panel.animate().translationX(dp(380).toFloat()).setDuration(200).withEndAction {
                overlay.visibility = View.GONE
            }.start()
        }
        currentTrackMenu = TrackMenuType.NONE
        playerView?.requestFocus()
        scheduleHideOverlay()
    }

    private fun moveFocusInTrackMenu(direction: Int) {
        // trackSelectorPanel is a LinearLayout whose children are the menu items
        val container = trackSelectorPanel ?: return
        val focusables = mutableListOf<View>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child.isFocusable) focusables.add(child)
        }
        if (focusables.isEmpty()) return
        val currentIndex = focusables.indexOf(currentFocus)
        val newIndex = (currentIndex + direction).coerceIn(0, focusables.lastIndex)
        focusables[newIndex].requestFocus()
    }

    // ── Control overlays (title + bottom bar + seek bar) ───────────────

    private fun showControlOverlays(keepPlaybackFocus: Boolean = true) {
        // Only animate in if not already visible — prevents flicker during seeking
        titleOverlay?.apply {
            if (visibility != View.VISIBLE) {
                visibility = View.VISIBLE
                alpha = 0f
                animate().alpha(1f).setDuration(200).start()
            } else {
                animate().cancel()
                alpha = 1f
            }
        }
        bottomBar?.apply {
            if (visibility != View.VISIBLE) {
                visibility = View.VISIBLE
                alpha = 0f
                animate().alpha(1f).setDuration(200).start()
            } else {
                animate().cancel()
                alpha = 1f
            }
        }
        seekBarLayout?.apply {
            if (visibility != View.VISIBLE) {
                visibility = View.VISIBLE
                alpha = 0f
                animate().alpha(1f).setDuration(200).start()
            } else {
                animate().cancel()
                alpha = 1f
            }
        }

        val focusedText = currentFocus as? TextView
        val isControlFocused = focusedText != null && controlButtons.contains(focusedText)
        val isSeekFocused = currentFocus === seekBar
        if (currentTrackMenu == TrackMenuType.NONE && keepPlaybackFocus && !isControlFocused && !isSeekFocused) {
            playerView?.post { playerView?.requestFocus() }
        }

        scheduleHideOverlay()
    }

    private fun hideControlOverlays() {
        titleOverlay?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
            titleOverlay?.visibility = View.GONE
        }?.start()
        bottomBar?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
            bottomBar?.visibility = View.GONE
        }?.start()
        seekBarLayout?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
            seekBarLayout?.visibility = View.GONE
        }?.start()
    }

    private fun scheduleHideOverlay() {
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY)
    }

    private fun applySeekBarFocusStyle(hasFocus: Boolean) {
        val sb = seekBar ?: return
        sb.progressTintList = ColorStateList.valueOf(Color.parseColor(if (hasFocus) SEEK_PROGRESS_FOCUSED else SEEK_PROGRESS_IDLE))
        sb.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor(if (hasFocus) SEEK_TRACK_FOCUSED else SEEK_TRACK_IDLE))
        sb.secondaryProgressTintList = ColorStateList.valueOf(Color.parseColor(if (hasFocus) SEEK_TRACK_FOCUSED else SEEK_TRACK_IDLE))
        sb.thumbTintList = ColorStateList.valueOf(Color.parseColor(if (hasFocus) SEEK_THUMB_FOCUSED else SEEK_THUMB_IDLE))
        sb.splitTrack = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            sb.outlineAmbientShadowColor = if (hasFocus) Color.parseColor("#80FFD970") else Color.TRANSPARENT
            sb.outlineSpotShadowColor = if (hasFocus) Color.parseColor("#80FFD970") else Color.TRANSPARENT
        }
        sb.elevation = if (hasFocus) dp(8).toFloat() else 0f

        timeCurrentText?.setTextColor(Color.parseColor(if (hasFocus) "#FFD970" else "#FFFFFF"))
        timeDurationText?.setTextColor(Color.parseColor(if (hasFocus) "#FFD970" else "#AAFFFFFF"))
        sb.invalidate()
    }

    private fun focusFirstControlButton() {
        bottomBar?.post { controlButtons.firstOrNull()?.requestFocus() }
    }

    private fun moveControlFocus(direction: Int) {
        val focusedText = currentFocus as? TextView ?: return
        val index = controlButtons.indexOf(focusedText)
        if (index < 0) return

        val nextIndex = (index + direction).coerceIn(0, controlButtons.lastIndex)
        controlButtons.getOrNull(nextIndex)?.requestFocus()
    }

    private fun showPlaybackBadge(showPauseIcon: Boolean) {
        val badge = playbackBadge ?: return
        handler.removeCallbacks(hidePlaybackBadgeRunnable)

        badge.text = if (showPauseIcon) "❚❚" else "▶"
        badge.visibility = View.VISIBLE
        badge.alpha = 0f
        badge.scaleX = 0.9f
        badge.scaleY = 0.9f
        badge.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(120)
            .start()

        handler.postDelayed(hidePlaybackBadgeRunnable, PLAYBACK_BADGE_HIDE_DELAY)
    }

    private fun togglePlaybackWithFeedback() {
        player?.let { exo ->
            val wasPlaying = exo.playWhenReady
            exo.playWhenReady = !wasPlaying
            // Show performed action: pause icon when pausing, play icon when resuming
            showPlaybackBadge(showPauseIcon = wasPlaying)
        }
    }

    // ── Key handling ───────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If error overlay is visible, lock navigation inside Retry/Close
        if (errorOverlay != null) {
            val retry = errorRetryButton
            val close = errorCloseButton
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (currentFocus === retry) close?.requestFocus() else close?.requestFocus() ?: retry?.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (currentFocus === close) retry?.requestFocus() else retry?.requestFocus() ?: close?.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> true
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    (currentFocus as? View)?.performClick()
                    true
                }
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_ESCAPE -> {
                    requestCloseToDetail()
                    true
                }
                else -> true
            }
        }

        // If track menu is open, trap focus inside the menu
        if (currentTrackMenu != TrackMenuType.NONE) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    dismissTrackMenu()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveFocusInTrackMenu(+1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    moveFocusInTrackMenu(-1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> super.onKeyDown(keyCode, event)
            }
        }

        val focusedText = currentFocus as? TextView
        val isControlFocused = focusedText != null && controlButtons.contains(focusedText)
        val isSeekFocused = currentFocus === seekBar

        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                requestCloseToDetail()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlaybackWithFeedback()
                showControlOverlays()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (isControlFocused) {
                    focusedText?.performClick()
                    showControlOverlays(keepPlaybackFocus = false)
                    true
                } else {
                    togglePlaybackWithFeedback()
                    showControlOverlays()
                    true
                }
            }
            // D-pad left/right: navigate control row when focused, otherwise seek
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isControlFocused) {
                    moveControlFocus(direction = -1)
                    showControlOverlays(keepPlaybackFocus = false)
                    true
                } else {
                    handleSeek(forward = false, event)
                    true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isControlFocused) {
                    moveControlFocus(direction = 1)
                    showControlOverlays(keepPlaybackFocus = false)
                    true
                } else {
                    handleSeek(forward = true, event)
                    true
                }
            }
            // 'A' key or Menu → Audio track selector
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_MENU -> {
                showTrackMenu(TrackMenuType.AUDIO)
                true
            }
            // 'S' key or Captions → Subtitle track selector
            KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_CAPTIONS -> {
                showTrackMenu(TrackMenuType.SUBTITLE)
                true
            }
            // D-pad flow: Playback -> SeekBar -> Control buttons, and back up the same path
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showControlOverlays(keepPlaybackFocus = false)
                when {
                    isControlFocused -> super.onKeyDown(keyCode, event)
                    isSeekFocused -> {
                        focusFirstControlButton()
                        true
                    }
                    else -> {
                        seekBar?.requestFocus()
                        true
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                showControlOverlays(keepPlaybackFocus = false)
                when {
                    isControlFocused -> {
                        seekBar?.requestFocus()
                        true
                    }
                    isSeekFocused -> {
                        playerView?.requestFocus()
                        true
                    }
                    else -> super.onKeyDown(keyCode, event)
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (!isSeeking && pendingSeekPosition < 0) {
                return super.onKeyUp(keyCode, event)
            }

            finalizeSeekSession(commitPending = true)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handleSeek(forward: Boolean, event: KeyEvent?) {
        val exo = player ?: return
        showControlOverlays()

        // Accelerate seek: more repeats = bigger jumps
        if (event?.repeatCount ?: 0 > 0) {
            seekRepeatCount++
        } else {
            seekRepeatCount = 0
        }
        isSeeking = true

        val multiplier = when {
            seekRepeatCount > 20 -> 6L
            seekRepeatCount > 10 -> 3L
            seekRepeatCount > 5 -> 2L
            else -> 1L
        }

        val seekAmount = SEEK_STEP_MS * multiplier
        val basePosition = if (pendingSeekPosition >= 0) pendingSeekPosition else exo.currentPosition
        val duration = exo.duration
        val hasKnownDuration = duration > 0 && duration != C.TIME_UNSET
        val newPosition = if (forward) {
            if (hasKnownDuration) {
                (basePosition + seekAmount).coerceAtMost(duration)
            } else {
                (basePosition + seekAmount).coerceAtLeast(0)
            }
        } else {
            (basePosition - seekAmount).coerceAtLeast(0)
        }

        // Update seek bar instantly for visual feedback
        pendingSeekPosition = newPosition
        if (hasKnownDuration) {
            seekBar?.progress = ((newPosition * 1000) / duration).toInt()
            timeCurrentText?.text = formatTime(newPosition)
        } else {
            timeCurrentText?.text = formatTime(newPosition)
        }

        // Live scrub: keep video position in sync with the progress bar on every step.
        exo.seekTo(newPosition)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    private var wasPlayingBeforePause = false

    override fun onPause() {
        super.onPause()
        wasPlayingBeforePause = player?.playWhenReady == true
        player?.playWhenReady = false
    }

    override fun onResume() {
        super.onResume()
        if (wasPlayingBeforePause) {
            player?.playWhenReady = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(closeReceiver) } catch (_: Exception) {}

        // Fallback in case close path wasn't initiated through Back/Close action.
        if (!closeEventSent) {
            syncProgressToWebApp()
            sendClosedEventOnce()
        }

        player?.release()
        player = null
    }

    // ── Error overlay ────────────────────────────────────────────────

    private var errorOverlay: FrameLayout? = null
    private var errorRetryButton: TextView? = null
    private var errorCloseButton: TextView? = null

    private fun showErrorOverlay(message: String) {
        if (errorOverlay != null) return

        val root = playerView?.parent as? FrameLayout ?: return
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#E6000000"))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(48), dp(48), dp(48))
        }

        val errorIcon = TextView(this).apply {
            text = "⚠"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            gravity = Gravity.CENTER
        }
        content.addView(errorIcon)

        val errorTitle = TextView(this).apply {
            text = "Playback Error"
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }
        content.addView(errorTitle)

        val errorMsg = TextView(this).apply {
            text = message
            setTextColor(Color.parseColor(TEXT_SECONDARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            maxLines = 3
        }
        content.addView(errorMsg)

        val retryBtn = createControlButton("↻ Retry") {
            errorOverlay?.let { root.removeView(it) }
            errorOverlay = null
            errorRetryButton = null
            errorCloseButton = null
            player?.prepare()
            player?.playWhenReady = true
        }
        val retryParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24); gravity = Gravity.CENTER }
        content.addView(retryBtn, retryParams)

        val closeBtn = createControlButton("✕ Close") { requestCloseToDetail() }
        val closeParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12); gravity = Gravity.CENTER }
        content.addView(closeBtn, closeParams)

        // Use stable generated IDs and lock focus inside Retry/Close
        retryBtn.id = View.generateViewId()
        closeBtn.id = View.generateViewId()
        retryBtn.nextFocusDownId = closeBtn.id
        closeBtn.nextFocusUpId = retryBtn.id
        retryBtn.nextFocusUpId = retryBtn.id
        closeBtn.nextFocusDownId = closeBtn.id
        retryBtn.nextFocusLeftId = retryBtn.id
        retryBtn.nextFocusRightId = retryBtn.id
        closeBtn.nextFocusLeftId = closeBtn.id
        closeBtn.nextFocusRightId = closeBtn.id

        errorRetryButton = retryBtn
        errorCloseButton = closeBtn

        overlay.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        errorOverlay = overlay
        retryBtn.post { retryBtn.requestFocus() }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }

    /** Known shorthand labels used in torrents/streams → full readable name */
    private val shorthandReplacements = mapOf(
        "VFF" to "French",
        "VFQ" to "French (Quebec)",
        "VFI" to "French (International)",
        "VF" to "French",
        "VO" to "Original",
        "VOF" to "Original (French sub)",
        "VOSTFR" to "Original (French sub)",
        "VA" to "English",
        "VE" to "English",
        "MULTI" to "Multi-language",
    )

    private fun formatSubtitleLabel(rawLabel: String?, rawLang: String?): String {
        val label = rawLabel?.trim().orEmpty()
        val langCode = normalizeLanguageCode(rawLang)
        val labelAsCode = normalizeLanguageCode(label)

        val resolvedFromLang = languageNameFromCode(langCode)
        val resolvedFromLabel = languageNameFromCode(labelAsCode)

        // Replace known shorthand prefixes (e.g. "VFF - AC3 5.1" → "French - AC3 5.1")
        val cleaned = replaceShorthands(label)

        val primaryName = when {
            cleaned.isBlank() && resolvedFromLang != null -> resolvedFromLang
            cleaned != label -> cleaned
            labelAsCode != null && resolvedFromLabel != null -> resolvedFromLabel
            langCode != null && label.equals(langCode, ignoreCase = true) && resolvedFromLang != null -> resolvedFromLang
            label.length in 2..3 && resolvedFromLabel != null -> resolvedFromLabel
            label.isNotBlank() -> {
                // Label is a source name (e.g. "LostFilm", "NewStudio"), prepend language if available
                if (resolvedFromLang != null) "$resolvedFromLang\n$label" else label
            }
            resolvedFromLang != null -> resolvedFromLang
            else -> "Unknown"
        }
        return primaryName
    }

    /** Replace known shorthand abbreviations at the start of a label */
    private fun replaceShorthands(label: String): String {
        if (label.isBlank()) return label
        // Check for exact match first (label is just the shorthand)
        val upperLabel = label.uppercase().trim()
        if (shorthandReplacements.containsKey(upperLabel)) {
            return shorthandReplacements[upperLabel]!!
        }
        // Check for shorthand at the start followed by separator (e.g. "VFF - AC3 5.1")
        for ((short, full) in shorthandReplacements) {
            val pattern = Regex("^${Regex.escape(short)}(\\s*[-–—:]\\s*)", RegexOption.IGNORE_CASE)
            val match = pattern.find(label)
            if (match != null) {
                return full + match.groupValues[1] + label.substring(match.range.last + 1)
            }
        }
        return label
    }

    private fun normalizeLanguageCode(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val cleaned = value.trim().lowercase().replace('_', '-')
        val base = cleaned.substringBefore('-')
        return base.takeIf { it.length in 2..3 && it.all(Char::isLetter) }
    }

    private fun languageNameFromCode(code: String?): String? {
        if (code == null) return null

        // ISO-639-1 (2-letter), ex: en -> English
        if (code.length == 2) {
            val locale = Locale(code)
            val name = locale.getDisplayLanguage(Locale.ENGLISH)
            if (name.isNotBlank() && !name.equals(code, ignoreCase = true)) return name
        }

        // ISO-639-2 (3-letter), ex: eng -> English
        if (code.length == 3) {
            for (iso2 in Locale.getISOLanguages()) {
                try {
                    val locale = Locale(iso2)
                    if (locale.isO3Language.equals(code, ignoreCase = true)) {
                        val name = locale.getDisplayLanguage(Locale.ENGLISH)
                        if (name.isNotBlank()) return name
                    }
                } catch (_: Exception) {
                    // Ignore malformed/unsupported locale entries
                }
            }
        }

        return null
    }

    private fun guessMimeType(url: String): String = when {
        url.endsWith(".srt", true) -> MimeTypes.APPLICATION_SUBRIP
        url.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
        url.endsWith(".ass", true) || url.endsWith(".ssa", true) -> MimeTypes.TEXT_SSA
        url.endsWith(".ttml", true) -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    private fun jsonArrayToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
    }

    // ── Audio codec fallback ───────────────────────────────────────────

    /**
     * Known problematic MIME types that many devices can't hardware-decode.
     * INTENTIONALLY EMPTY: DTS/DTS-HD/TrueHD are NOT blocked here because
     * they work fine via HDMI passthrough on devices like the Nvidia Shield.
     * Blocking them proactively prevents passthrough from working.
     * If playback truly fails, the runtime error handler will catch it and
     * fall back automatically.
     */
    private val unsafeAudioMimeTypes = emptySet<String>()

    /** Safe fallback MIME types — universally supported on all Android devices */
    private val safeAudioMimeTypes = setOf(
        MimeTypes.AUDIO_AAC,
        MimeTypes.AUDIO_MPEG,       // MP3
        MimeTypes.AUDIO_OPUS,
        MimeTypes.AUDIO_VORBIS,
    )

    /** Conditionally safe — require hardware check before using as fallback */
    private val conditionalAudioMimeTypes = setOf(
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_E_AC3_JOC,
    )

    /** Try to extract failing audio MIME from error message */
    private fun extractAudioMimeFromError(msg: String): String? {
        // Pattern: "format=Format(... audio/vnd.dts ...)" or "audio/true-hd"
        val regex = Regex("""(audio/[\w.\-]+)""")
        return regex.find(msg)?.groupValues?.getOrNull(1)
    }

    /** Check if a specific audio MIME type is supported by the device's hardware decoders */
    @OptIn(UnstableApi::class)
    private fun isAudioMimeSupported(mimeType: String): Boolean {
        if (mimeType in excludedAudioMimeTypes) return false
        return try {
            val codecList = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
            val format = android.media.MediaFormat.createAudioFormat(mimeType, 48000, 6)
            codecList.findDecoderForFormat(format) != null
        } catch (_: Exception) {
            // If we can't determine, assume safe types are OK
            mimeType in safeAudioMimeTypes
        }
    }

    /** On audio codec error: find a safe fallback audio track and retry */
    @OptIn(UnstableApi::class)
    private fun tryAudioFallback(exo: ExoPlayer) {
        val tracks = exo.currentTracks
        val currentPos = exo.currentPosition

        // First pass: universally safe codecs (AAC, MP3, Opus, Vorbis)
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val mime = format.sampleMimeType ?: continue
                if (mime in safeAudioMimeTypes && mime !in excludedAudioMimeTypes) {
                    selectAudioTrackAndRetry(exo, group, i, currentPos)
                    return
                }
            }
        }

        // Second pass: conditional codecs (AC3/EAC3).
        // We intentionally try them without a strict pre-check because codec-query
        // can return false-negatives on some TV/AVR passthrough setups.
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val mime = format.sampleMimeType ?: continue
                if (mime in conditionalAudioMimeTypes && mime !in excludedAudioMimeTypes) {
                    selectAudioTrackAndRetry(exo, group, i, currentPos)
                    return
                }
            }
        }

        // No safe track found — show error
        bufferingOverlay?.visibility = View.GONE
        loadingBackdrop?.visibility = View.GONE
        showErrorOverlay("No supported audio track found")
    }

    /** Helper: select a specific audio track and restart playback */
    @OptIn(UnstableApi::class)
    private fun selectAudioTrackAndRetry(exo: ExoPlayer, group: Tracks.Group, trackIndex: Int, positionMs: Long) {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex)))
            .build()
        exo.prepare()
        exo.seekTo(positionMs)
        exo.playWhenReady = true
    }

    /** Proactively check audio tracks and prefer supported codecs over unsupported ones */
    @OptIn(UnstableApi::class)
    private fun proactiveAudioCodecCheck(exo: ExoPlayer) {
        val tracks = exo.currentTracks

        // Check if the currently selected audio track is supported
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val format = group.getTrackFormat(i)
                val mime = format.sampleMimeType ?: continue
                // Only proactively switch for clearly unsafe tracks that fail capability checks.
                // Conditional tracks (AC3/EAC3) are allowed and handled by runtime fallback.
                if ((mime in unsafeAudioMimeTypes) && !isAudioMimeSupported(mime)) {
                    switchToSafeAudioTrack(exo)
                    return
                }
            }
        }
    }

    /** Switch to the best safe audio track available */
    @OptIn(UnstableApi::class)
    private fun switchToSafeAudioTrack(exo: ExoPlayer) {
        val tracks = exo.currentTracks
        val langPrefs = preferredAudioLangs.map { it.lowercase().take(3) }

        data class Candidate(val group: Tracks.Group, val index: Int, val langMatch: Boolean, val channels: Int, val isSafe: Boolean)
        val candidates = mutableListOf<Candidate>()

        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val mime = format.sampleMimeType ?: continue
                if (mime in excludedAudioMimeTypes) continue
                val safe = mime in safeAudioMimeTypes
                val conditionalOk = mime in conditionalAudioMimeTypes
                if (safe || conditionalOk) {
                    val lang = format.language?.lowercase()?.take(3) ?: ""
                    val langMatch = langPrefs.isEmpty() || langPrefs.any { lang.startsWith(it) }
                    candidates.add(Candidate(group, i, langMatch, format.channelCount, safe))
                }
            }
        }

        // Sort: universally safe first, then language match, then highest channel count
        val best = candidates.sortedWith(
            compareByDescending<Candidate> { it.isSafe }
                .thenByDescending { it.langMatch }
                .thenByDescending { it.channels }
        ).firstOrNull() ?: return

        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(best.group.mediaTrackGroup, listOf(best.index)))
            .build()
    }

    /**
     * Switch audio track with a full prepare() — cleanly resets the decoder pipeline.
     * This prevents codec conflicts when switching between different audio formats
     * (e.g., AAC → TrueHD Atmos). Used by the manual Audio track selector.
     */
    @OptIn(UnstableApi::class)
    private fun switchAudioTrackWithPrepare(exo: ExoPlayer, group: Tracks.Group, trackIndex: Int) {
        val currentPos = exo.currentPosition
        val wasPlaying = exo.playWhenReady

        // Reset fallback state for the new track attempt
        audioFallbackAttempts = 0
        tunnelingDisabledForFallback = false
        softwareDecoderRetried = false
        excludedAudioMimeTypes.clear()

        // Set the track override + re-enable tunneling for best quality
        trackSelector?.setParameters(
            trackSelector!!.buildUponParameters()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex)))
                .setTunnelingEnabled(true)
                .build()
        )

        // Full prepare() to cleanly rebuild the decoder pipeline
        exo.stop()
        lastSelectedMediaSource?.let { exo.setMediaSource(it) }
        exo.prepare()
        exo.seekTo(currentPos)
        exo.playWhenReady = wasPlaying
    }

    /**
     * Retry current track with tunneling disabled.
     * Tunneled playback can cause codec init failures on some devices/formats.
     */
    @OptIn(UnstableApi::class)
    private fun retryWithTunnelingDisabled(exo: ExoPlayer) {
        val currentPos = exo.currentPosition

        trackSelector?.setParameters(
            trackSelector!!.buildUponParameters()
                .setTunnelingEnabled(false)
                .build()
        )

        exo.stop()
        lastSelectedMediaSource?.let { exo.setMediaSource(it) }
        exo.prepare()
        exo.seekTo(currentPos)
        exo.playWhenReady = true
    }

    /**
     * Retry with a fresh ExoPlayer instance using software decoders.
     * Rebuilds the player with EXTENSION_RENDERER_MODE_ON (use built-in decoders)
     * and tunneling disabled — this matches how Stremio handles codec fallback.
     */
    @OptIn(UnstableApi::class)
    private fun retryWithSoftwareDecoder(exo: ExoPlayer) {
        val currentPos = exo.currentPosition
        val currentParams = exo.trackSelectionParameters

        // Rebuild renderers factory without extension preference
        val softwareRenderersFactory = DefaultRenderersFactory(this).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setEnableAudioTrackPlaybackParams(true)
        }

        // Keep tunneling off for software decode
        trackSelector?.setParameters(
            trackSelector!!.buildUponParameters()
                .setTunnelingEnabled(false)
                .build()
        )

        // Create new player with software decoders
        val newPlayer = ExoPlayer.Builder(this, softwareRenderersFactory)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 180_000, 1_000, 3_000)
                    .setBackBuffer(60_000, true)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()

        // Transfer state
        newPlayer.trackSelectionParameters = currentParams
        playerView?.player = newPlayer
        lastSelectedMediaSource?.let { newPlayer.setMediaSource(it) }

        // Copy over the listener from old player
        newPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                handler.post {
                    if (isRecoverableAudioError(newPlayer, error) && audioFallbackAttempts < MAX_AUDIO_FALLBACK_ATTEMPTS) {
                        handleRecoverableAudioError(newPlayer, error)
                    } else if (isSourceError(error) && sourceRetryAttempts < MAX_SOURCE_RETRIES) {
                        sourceRetryAttempts++
                        val retryPosition = newPlayer.currentPosition
                        bufferingOverlay?.visibility = View.VISIBLE
                        handler.postDelayed({
                            try {
                                newPlayer.prepare()
                                if (retryPosition > 0) newPlayer.seekTo(retryPosition)
                                newPlayer.play()
                            } catch (_: Exception) {
                                showErrorOverlay("Playback failed")
                            }
                        }, 1500L * sourceRetryAttempts)
                    } else {
                        val errorMsg = error.localizedMessage ?: ""
                        bufferingOverlay?.visibility = View.GONE
                        loadingBackdrop?.visibility = View.GONE
                        showErrorOverlay(errorMsg.ifEmpty { "Playback failed" })
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        bufferingOverlay?.visibility = View.GONE
                        loadingBackdrop?.animate()?.alpha(0f)?.setDuration(600)?.withEndAction {
                            loadingBackdrop?.visibility = View.GONE
                        }?.start()
                    }
                    Player.STATE_BUFFERING -> {
                        if (loadingBackdrop?.visibility == View.GONE) {
                            bufferingOverlay?.visibility = View.VISIBLE
                        }
                    }
                }
            }
        })

        // Stop old player and switch
        exo.stop()
        exo.release()
        player = newPlayer

        newPlayer.prepare()
        newPlayer.seekTo(currentPos)
        newPlayer.playWhenReady = true
    }

    /** Check if error is a source/network error (HTTP failures, timeouts, segment errors) */
    private fun isSourceError(error: PlaybackException): Boolean {
        return error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
               error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
               error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
               error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
               error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
               error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
               (error.localizedMessage?.lowercase()?.contains("source") == true)
    }

    @OptIn(UnstableApi::class)
    private fun isRecoverableAudioError(exo: ExoPlayer, error: PlaybackException): Boolean {
        val errorMsg = buildString {
            append(error.localizedMessage.orEmpty())
            append(" ")
            append(error.cause?.message.orEmpty())
        }.lowercase()

        val errorCodeName = PlaybackException.getErrorCodeName(error.errorCode).lowercase()
        val selectedAudioMimes = getSelectedAudioMimeTypes(exo)

        val mentionsAudioRenderer = errorMsg.contains("mediocodecaudiorenderer")
            || errorMsg.contains("audiosink")
            || errorMsg.contains("audiotrack")
            || errorCodeName.contains("audio_track")

        val mentionsAudioMime = errorMsg.contains("audio/")
            || selectedAudioMimes.any { mime -> errorMsg.contains(mime.lowercase()) }

        val decoderFailure = errorCodeName.contains("decoder_init_failed")
            || errorCodeName.contains("decoding_failed")
            || errorCodeName.contains("audio_track_init_failed")
            || errorCodeName.contains("audio_track_write_failed")

        return mentionsAudioRenderer || mentionsAudioMime || (decoderFailure && selectedAudioMimes.isNotEmpty())
    }

    @OptIn(UnstableApi::class)
    private fun getSelectedAudioMimeTypes(exo: ExoPlayer): List<String> {
        val result = mutableListOf<String>()
        for (group in exo.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val mime = group.getTrackFormat(i).sampleMimeType ?: continue
                result.add(mime)
            }
        }
        return result
    }

    @OptIn(UnstableApi::class)
    private fun handleRecoverableAudioError(exo: ExoPlayer, error: PlaybackException) {
        audioFallbackAttempts++

        val errorMsg = buildString {
            append(error.localizedMessage.orEmpty())
            append(" ")
            append(error.cause?.message.orEmpty())
        }

        val failingMime = extractAudioMimeFromError(errorMsg)
            ?: getSelectedAudioMimeTypes(exo).firstOrNull()
        if (!failingMime.isNullOrEmpty()) {
            excludedAudioMimeTypes.add(failingMime)
        }

        val savedParams = lastWorkingAudioParams

        if (!tunnelingDisabledForFallback) {
            tunnelingDisabledForFallback = true
            retryWithTunnelingDisabled(exo)
            return
        }

        if (!softwareDecoderRetried) {
            softwareDecoderRetried = true
            retryWithSoftwareDecoder(exo)
            return
        }

        if (savedParams != null) {
            lastWorkingAudioParams = null
            revertToWorkingAudioTrack(exo, savedParams, lastWorkingAudioPosition)
            return
        }

        tryAudioFallback(exo)
    }

    /**
     * Revert to a previously working audio track after a manual selection failed.
     * Restores the saved track parameters and resumes from the saved position.
     */
    @OptIn(UnstableApi::class)
    private fun revertToWorkingAudioTrack(exo: ExoPlayer, savedParams: androidx.media3.common.TrackSelectionParameters, positionMs: Long) {
        audioFallbackAttempts = 0
        tunnelingDisabledForFallback = false

        trackSelector?.setParameters(
            trackSelector!!.buildUponParameters()
                .setTunnelingEnabled(true)
                .build()
        )
        exo.trackSelectionParameters = savedParams

        exo.stop()
        lastSelectedMediaSource?.let { exo.setMediaSource(it) }
        exo.prepare()
        exo.seekTo(positionMs)
        exo.playWhenReady = true
    }
}
