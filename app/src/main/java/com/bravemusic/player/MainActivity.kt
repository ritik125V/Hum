package com.bravemusic.player

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.bravemusic.player.adblock.BraveShield
import com.bravemusic.player.adblock.BraveShieldSyncWorker
import com.bravemusic.player.data.feed.FeedCacheRepository
import com.bravemusic.player.databinding.ActivityMainBinding
import com.bravemusic.player.playback.AudioDiskCache
import com.bravemusic.player.playback.MediaPlaybackService
import com.bravemusic.player.ui.PlayerBottomSheetController
import com.bravemusic.player.ui.SettingsBottomSheet
import com.bravemusic.player.ui.feed.FeedSectionAdapter
import com.bravemusic.player.databinding.LayoutPlayerBottomSheetBinding
import com.bravemusic.player.utils.SettingsManager
import com.bravemusic.player.webview.BraveMusicBridge
import com.bravemusic.player.webview.MusicWebChromeClient
import com.bravemusic.player.webview.MusicWebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var playerController: PlayerBottomSheetController
    private lateinit var feedRepository: FeedCacheRepository
    private lateinit var feedAdapter: FeedSectionAdapter
    private lateinit var audioCache: AudioDiskCache
    private lateinit var settings: SettingsManager
    private lateinit var webChromeClient: MusicWebChromeClient
    private var playbackService: MediaPlaybackService? = null
    private var isServiceBound = false
    private var isPlayerExpanded = false
    private var isDrawerOpen = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MediaPlaybackService.LocalBinder
            playbackService = binder.getService().apply {
                onPlayAction = { executeJs("window.BraveMusicControls && window.BraveMusicControls.play()") }
                onPauseAction = { executeJs("window.BraveMusicControls && window.BraveMusicControls.pause()") }
                onNextAction = { executeJs("window.BraveMusicControls && window.BraveMusicControls.next()") }
                onPreviousAction = { executeJs("window.BraveMusicControls && window.BraveMusicControls.previous()") }
            }
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 0. High display refresh rate (120Hz peak) & hardware acceleration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.preferredRefreshRate = 120f
        }
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // Handle System Bars / Window Insets: only pad top status bar so content never overlaps system icons
        // Left, right, and bottom remain 0 to eliminate any letterbox black rectangles
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, insets.top, 0, 0)
            windowInsets
        }

        // 1. Initialize Brave Shield Adblock Engine & Metrics
        BraveShield.initialize(applicationContext)
        com.bravemusic.player.adblock.BraveShieldMetrics.initialize(applicationContext)

        // 2. Schedule daily background updates for Brave Shield rules
        BraveShieldSyncWorker.schedulePeriodicSync(applicationContext)

        // 3. Initialize Audio Disk Cache for zero-data repeat plays
        audioCache = AudioDiskCache(applicationContext)
        settings = SettingsManager(applicationContext)

        // 4. Start & Bind Media Playback Service
        val serviceIntent = Intent(this, MediaPlaybackService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // 5. Request notification permission on Android 13+
        requestNotificationPermission()

        // 6. Setup WebView
        android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        setupWebView()

        // 7. Setup Slide-in / Slide-out Edge Navigation Drawer
        setupEdgeDrawer()

        // 8. Setup Native BottomSheet Player Controller
        playerController = PlayerBottomSheetController(
            binding = binding.layoutPlayerSheet,
            onPlayPauseAction = {
                executeJs("if (window.BraveMusicControls) { const v = document.querySelector('video'); if (v && !v.paused) window.BraveMusicControls.pause(); else window.BraveMusicControls.play(); }")
            },
            onNextAction = {
                executeJs("if (window.BraveMusicControls && window.BraveMusicControls.next) window.BraveMusicControls.next();")
            },
            onPreviousAction = {
                executeJs("if (window.BraveMusicControls && window.BraveMusicControls.previous) window.BraveMusicControls.previous();")
            },
            onSeekAction = { seconds ->
                executeJs("if (window.BraveMusicControls && window.BraveMusicControls.seekTo) window.BraveMusicControls.seekTo($seconds);")
            },
            onCollapseAction = {
                executeJs("if (window.BraveMusicControls && window.BraveMusicControls.collapsePlayer) window.BraveMusicControls.collapsePlayer();")
            }
        )

        // 9. Setup Native Home Feed (0ms Cold Start & SWR Caching)
        setupNativeFeed()

        // 10. Setup Back Press Handling: Player collapse, drawer close, native feed restore, and history navigation
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isDrawerOpen) {
                    closeDrawer()
                } else if (playerController.collapse()) {
                    // Collapsed native player sheet
                } else if (binding.nativeHomeContainer.visibility != View.VISIBLE) {
                    binding.nativeHomeContainer.visibility = View.VISIBLE
                } else if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    moveTaskToBack(true)
                }
            }
        })

        // 10. Load YouTube Music Mobile (cold start only)
        if (savedInstanceState == null) {
            binding.webView.loadUrl("https://music.youtube.com")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Zero-latency direct window composition (LAYER_TYPE_NONE removes extra FBO copy latency)
        binding.webView.setLayerType(View.LAYER_TYPE_NONE, null)
        binding.webView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        binding.webView.isVerticalScrollBarEnabled = false
        binding.webView.isHorizontalScrollBarEnabled = false
        binding.webView.isHapticFeedbackEnabled = false

        val webSettings = binding.webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.mediaPlaybackRequiresUserGesture = false
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        webSettings.defaultTextEncodingName = "utf-8"

        // Ultra low-RAM & smooth settings matching Brave/Chrome Mobile
        webSettings.offscreenPreRaster = false // Disable offscreen pre-raster to save RAM and stop UI thread jank
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = false // Mobile browsers do not run overview mode
        webSettings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL

        // Standard Chrome Mobile gesture recognizer with hidden UI controls
        // Preserves Android native touch slop deadband and fluid momentum scrolling (Spotify feel)
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.textZoom = 100

        // High priority renderer policy on Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.webView.setRendererPriorityPolicy(android.webkit.WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }

        // Native dark mode rendering without algorithmic GPU overhead (YouTube Music is inherently dark)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            webSettings.forceDark = WebSettings.FORCE_DARK_OFF
        }

        @Suppress("DEPRECATION")
        webSettings.setEnableSmoothTransition(true)

        // Clean Chrome Mobile user agent matching Brave Browser on Android
        // Forces YouTube Music to serve modern Lit/Polymer UI with hardware-accelerated animations
        webSettings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        // Full cookie and login persistence support
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        // Register Javascript Bridge for MediaSession & track metadata
        binding.webView.addJavascriptInterface(
            BraveMusicBridge(
                onTrackChangedCallback = { title, artist, album, artworkUrl ->
                    runOnUiThread {
                        playbackService?.updateMetadata(title, artist, album, artworkUrl)
                        playerController.updateTrack(title, artist, artworkUrl)
                    }
                },
                onPlaybackStateCallback = { isPlaying ->
                    runOnUiThread {
                        playbackService?.updatePlaybackState(isPlaying)
                        playerController.updatePlaybackState(isPlaying)
                    }
                },
                onPlayerStateCallback = { isExpanded ->
                    isPlayerExpanded = isExpanded
                },
                showKeyboardCallback = {
                    runOnUiThread {
                        binding.webView.requestFocus(View.FOCUS_DOWN)
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                        imm?.showSoftInput(binding.webView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                },
                hideKeyboardCallback = {
                    runOnUiThread {
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                        imm?.hideSoftInputFromWindow(binding.webView.windowToken, 0)
                    }
                },
                isAudioOnlyCallback = {
                    settings.isAudioOnlyEnabled
                },
                onProgressCallback = { currentSeconds, totalSeconds ->
                    runOnUiThread {
                        playerController.updateProgress(currentSeconds, totalSeconds)
                    }
                },
                onHomeFeedLoadedCallback = { feedJson ->
                    val sections = feedRepository.parseFeedJson(feedJson)
                    if (sections.isNotEmpty()) {
                        runOnUiThread {
                            feedAdapter.updateSections(sections)
                        }
                        lifecycleScope.launch(Dispatchers.IO) {
                            feedRepository.saveFeedCache(feedJson)
                        }
                    }
                }
            ),
            "BraveMusicBridge"
        )

        webChromeClient = MusicWebChromeClient(binding.progressBar, binding.dropLoadingContainer)
        binding.webView.webChromeClient = webChromeClient
        binding.webView.webViewClient = MusicWebViewClient(
            context = applicationContext,
            audioCache = audioCache,
            onPageLoaded = {
                runOnUiThread {
                    webChromeClient.hideDropLoading()
                    applyAudioOnlyMode()
                }
            }
        )
    }

    private fun executeJs(script: String, callback: ((String?) -> Unit)? = null) {
        binding.webView.post {
            binding.webView.evaluateJavascript(script) { res ->
                callback?.invoke(res)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupEdgeDrawer() {
        val density = resources.displayMetrics.density
        // Dock width (52dp dock + 1dp separator) = 53dp
        val dockWidth = 53f * density
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        // Initialize collapsed offscreen to the RIGHT
        binding.drawerContainer.post {
            binding.drawerContainer.translationX = dockWidth
        }

        var startX = 0f
        var isDragging = false

        binding.btnDrawerToggle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - startX
                    if (!isDragging && Math.abs(deltaX) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val base = if (isDrawerOpen) 0f else dockWidth
                        val newTransX = (base + deltaX).coerceIn(0f, dockWidth)
                        binding.drawerContainer.translationX = newTransX
                        val progress = 1f - (newTransX / dockWidth)
                        binding.drawerScrim.visibility = View.VISIBLE
                        binding.drawerScrim.alpha = progress * 0.8f
                        binding.ivDrawerChevron.rotation = progress * 180f
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        toggleDrawer()
                    } else {
                        val currentTransX = binding.drawerContainer.translationX
                        if (currentTransX < dockWidth * 0.5f) {
                            openDrawer()
                        } else {
                            closeDrawer()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        binding.drawerScrim.setOnClickListener {
            closeDrawer()
        }

        binding.btnDrawerHome.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            it.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
                it.animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start()
                closeDrawer()
                navigateHome()
            }.start()
        }

        binding.btnDrawerSettings.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            it.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
                it.animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start()
                closeDrawer()
                openSettings()
            }.start()
        }
    }

    private fun openDrawer() {
        if (isDrawerOpen) return
        isDrawerOpen = true
        binding.drawerScrim.visibility = View.VISIBLE
        binding.drawerScrim.alpha = 0f
        binding.drawerScrim.animate()
            .alpha(1f)
            .setDuration(220)
            .start()

        binding.drawerContainer.animate()
            .translationX(0f)
            .setDuration(240)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
            .start()

        binding.ivDrawerChevron.animate()
            .rotation(180f)
            .setDuration(240)
            .start()
    }

    private fun closeDrawer() {
        if (!isDrawerOpen) return
        isDrawerOpen = false
        binding.drawerScrim.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction { binding.drawerScrim.visibility = View.GONE }
            .start()

        val dockWidth = 53f * resources.displayMetrics.density
        binding.drawerContainer.animate()
            .translationX(dockWidth)
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
            .start()

        binding.ivDrawerChevron.animate()
            .rotation(0f)
            .setDuration(200)
            .start()
    }

    private fun toggleDrawer() {
        if (isDrawerOpen) closeDrawer() else openDrawer()
    }

    private fun navigateHome() {
        binding.nativeHomeContainer.visibility = View.VISIBLE
        binding.rvHomeFeed.smoothScrollToPosition(0)
        webChromeClient.showDropLoading()
        executeJs("if (window.BraveMusicControls && window.BraveMusicControls.goHome) { window.BraveMusicControls.goHome(); } else { window.location.href = 'https://music.youtube.com/'; }")
    }

    private fun setupNativeFeed() {
        feedRepository = FeedCacheRepository(applicationContext)
        feedAdapter = FeedSectionAdapter { item ->
            // Immediate visual feedback (0ms latency)
            playerController.updateTrack(item.title, item.subtitle, item.thumbnailUrl)

            // Dispatch playback action to web engine
            val js = "if (window.BraveMusicControls && window.BraveMusicControls.playTrack) { " +
                    "window.BraveMusicControls.playTrack('${item.videoId ?: ""}', '${item.playlistId ?: ""}'); " +
                    "} else { " +
                    "window.location.href = 'https://music.youtube.com/watch?v=${item.videoId ?: ""}'; }"
            executeJs(js)
        }

        binding.rvHomeFeed.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.rvHomeFeed.adapter = feedAdapter
        binding.rvHomeFeed.setHasFixedSize(false)

        // Instant 0ms Cold Start: Load cached feed synchronously from disk
        val cached = feedRepository.getCachedFeed()
        feedAdapter.updateSections(cached)

        // Top Bar Actions
        binding.btnTopSettings.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            openSettings()
        }

        binding.btnTopSearch.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            binding.nativeHomeContainer.visibility = View.GONE
            executeJs("const searchBtn = document.querySelector('ytmusic-search-box, [aria-label*=\"Search\" i]'); if (searchBtn) searchBtn.click();")
        }
    }

    private fun openSettings() {
        val settingsSheet = SettingsBottomSheet(
            onSettingsChanged = {
                playbackService?.refreshMetadataFromSettings()
                applyAudioOnlyMode()
            }
        )
        settingsSheet.show(supportFragmentManager, SettingsBottomSheet.TAG)
    }

    private fun applyAudioOnlyMode() {
        val enabled = settings.isAudioOnlyEnabled
        executeJs("if (window.BraveMusicControls && window.BraveMusicControls.setAudioOnly) { window.BraveMusicControls.setAudioOnly($enabled); }")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        executeJs("if (window.BraveMusicControls && window.BraveMusicControls.onAppStateChanged) window.BraveMusicControls.onAppStateChanged(false);")
        // Do not call webView.onPause() to preserve uninterrupted background audio playback and timers
    }

    override fun onResume() {
        super.onResume()
        executeJs("if (window.BraveMusicControls && window.BraveMusicControls.onAppStateChanged) window.BraveMusicControls.onAppStateChanged(true);")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Aggressively free RAM when system is under memory pressure
        if (level >= TRIM_MEMORY_MODERATE) {
            binding.webView.clearCache(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CookieManager.getInstance().flush()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        binding.webView.destroy()
    }
}
