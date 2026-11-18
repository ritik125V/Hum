package com.bravemusic.player.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bravemusic.player.MainActivity
import com.bravemusic.player.R
import com.bravemusic.player.utils.SettingsManager
import kotlinx.coroutines.*
import java.net.URL

class MediaPlaybackService : Service() {

    private val binder = LocalBinder()
    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var settings: SettingsManager
    private var audioEngine: NativeAudioEngine? = null

    var onPlayAction: (() -> Unit)? = null
    var onPauseAction: (() -> Unit)? = null
    var onNextAction: (() -> Unit)? = null
    var onPreviousAction: (() -> Unit)? = null

    fun getAudioEngine(): NativeAudioEngine? = audioEngine

    private var lastRealTitle = "HUM"
    private var lastRealArtist = "Ad-free streaming"
    private var lastRealAlbum = "YouTube Music"
    private var lastRealArtworkUrl = ""

    private var currentTitle = "HUM"
    private var currentArtist = "Ad-free streaming"
    private var currentAlbumArt: Bitmap? = null
    private var isPlaying = false

    inner class LocalBinder : Binder() {
        fun getService(): MediaPlaybackService = this@MediaPlaybackService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        createNotificationChannel()
        initMediaSession()
        initWakeLock()
        initAudioEngine()
    }

    private fun initAudioEngine() {
        audioEngine = NativeAudioEngine(applicationContext).apply {
            onTrackEnded = {
                onNextAction?.invoke()
            }
        }
    }

    private fun initWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BraveMusic::PlaybackWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    private fun initMediaSession() {
        val mbrIntent = Intent(Intent.ACTION_MEDIA_BUTTON, null, this, androidx.media.session.MediaButtonReceiver::class.java)
        val mbrPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            mbrIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSessionCompat(this, "BraveMusicSession", null, mbrPendingIntent).apply {
            setMediaButtonReceiver(mbrPendingIntent)
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Log.d(TAG, "MediaSessionCompat: onPlay received")
                    if (audioEngine?.currentTrackTitle?.value?.isNotEmpty() == true) {
                        audioEngine?.play()
                    } else {
                        onPlayAction?.invoke()
                    }
                    updatePlaybackState(true)
                }

                override fun onPause() {
                    Log.d(TAG, "MediaSessionCompat: onPause received")
                    if (audioEngine?.isPlayingState?.value == true) {
                        audioEngine?.pause()
                    } else {
                        onPauseAction?.invoke()
                    }
                    updatePlaybackState(false)
                }

                override fun onSkipToNext() {
                    Log.d(TAG, "MediaSessionCompat: onSkipToNext received")
                    onNextAction?.invoke()
                }

                override fun onSkipToPrevious() {
                    Log.d(TAG, "MediaSessionCompat: onSkipToPrevious received")
                    onPreviousAction?.invoke()
                }

                override fun onStop() {
                    Log.d(TAG, "MediaSessionCompat: onStop received")
                    onPauseAction?.invoke()
                    updatePlaybackState(false)
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                        Log.d(TAG, "MediaSessionCompat: onMediaButtonEvent keyCode=${keyEvent.keyCode}")
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                onPlay()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                onPause()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                                if (isPlaying) onPause() else onPlay()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                onSkipToNext()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                onSkipToPrevious()
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            setSessionActivity(createContentIntent())
            isActive = true
        }

        if (settings.isCustomMetadataEnabled) {
            currentTitle = settings.customTitle
            currentArtist = settings.customArtist
            currentAlbumArt = getPresetBitmap(settings.artworkPreset)
        }

        applyMediaSessionMetadata()
        updatePlaybackState(false)
    }

    fun updatePlaybackState(playing: Boolean) {
        isPlaying = playing
        
        // Battery saver: Only hold CPU wake lock while audio is actively streaming
        if (playing) {
            wakeLock?.acquire(3 * 60 * 60 * 1000L) // 3 hours timeout safety
        } else {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }

        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
        )

        updateNotification()
    }

    fun updateMetadata(title: String, artist: String, album: String, artworkUrl: String) {
        val cleanTitle = title.trim()
        if (cleanTitle.isNotEmpty() && cleanTitle != "YouTube Music") {
            lastRealTitle = cleanTitle
            lastRealArtist = artist.ifEmpty { "YouTube Music" }
            lastRealAlbum = album.ifEmpty { "YouTube Music" }
            if (artworkUrl.isNotEmpty() && !artworkUrl.startsWith("data:")) {
                lastRealArtworkUrl = artworkUrl
            }
        } else if (lastRealTitle.isEmpty() && cleanTitle.isNotEmpty()) {
            lastRealTitle = cleanTitle
            lastRealArtist = artist.ifEmpty { "YouTube Music" }
            lastRealAlbum = album.ifEmpty { "YouTube Music" }
            if (artworkUrl.isNotEmpty() && !artworkUrl.startsWith("data:")) {
                lastRealArtworkUrl = artworkUrl
            }
        }

        // Check if user enabled custom metadata / disguise mode
        if (settings.isCustomMetadataEnabled) {
            currentTitle = settings.customTitle
            currentArtist = settings.customArtist
        } else {
            currentTitle = lastRealTitle
            currentArtist = lastRealArtist
        }

        serviceScope.launch(Dispatchers.IO) {
            currentAlbumArt = when {
                // If custom artwork preset is selected, use it
                settings.isCustomMetadataEnabled && settings.artworkPreset != "default" -> {
                    getPresetBitmap(settings.artworkPreset)
                }
                // Otherwise use real album artwork
                lastRealArtworkUrl.isNotEmpty() && !lastRealArtworkUrl.startsWith("data:") -> {
                    try {
                        val cleanUrl = if (lastRealArtworkUrl.startsWith("//")) "https:$lastRealArtworkUrl" else lastRealArtworkUrl
                        val url = URL(cleanUrl)
                        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                            connectTimeout = 8000
                            readTimeout = 8000
                            instanceFollowRedirects = true
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                            setRequestProperty("Referer", "https://music.youtube.com/")
                            setRequestProperty("Accept", "image/webp,image/png,image/jpeg,image/*")
                        }
                        val bmp = BitmapFactory.decodeStream(conn.inputStream)
                        conn.disconnect()
                        bmp
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to download artwork: $lastRealArtworkUrl", e)
                        null
                    }
                }
                else -> null
            } ?: getDefaultArtworkBitmap()

            withContext(Dispatchers.Main) {
                applyMediaSessionMetadata()
                updateNotification()
            }
        }
    }

    fun refreshMetadataFromSettings() {
        updateMetadata(lastRealTitle, lastRealArtist, lastRealAlbum, lastRealArtworkUrl)
    }

    private fun getDefaultArtworkBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.parseColor("#0F1015"))
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground)
        if (drawable != null) {
            val inset = 64
            drawable.setBounds(inset, inset, 512 - inset, 512 - inset)
            drawable.draw(canvas)
        }
        return bitmap
    }

    private fun getPresetBitmap(preset: String): Bitmap? {
        val resId = when (preset) {
            "brave" -> R.drawable.bg_preset_brave
            "cyberpunk" -> R.drawable.bg_preset_cyberpunk
            "minimal" -> R.drawable.bg_preset_minimal
            else -> return null
        }
        val drawable = ContextCompat.getDrawable(this, resId) ?: return null
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun applyMediaSessionMetadata() {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, lastRealAlbum)

        val art = currentAlbumArt ?: getDefaultArtworkBitmap()
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)

        if (lastRealArtworkUrl.isNotEmpty()) {
            val cleanUrl = if (lastRealArtworkUrl.startsWith("//")) "https:$lastRealArtworkUrl" else lastRealArtworkUrl
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, cleanUrl)
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, cleanUrl)
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, cleanUrl)
        }

        mediaSession?.setMetadata(metadataBuilder.build())
    }

    private fun updateNotification() {
        val notification = buildNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createContentIntent(): PendingIntent {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildNotification(): Notification {
        val contentIntent = createContentIntent()

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val playPauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_TOGGLE_PLAY },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_PREVIOUS },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nextIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setSmallIcon(R.drawable.ic_notification_music)
            .setLargeIcon(currentAlbumArt ?: getDefaultArtworkBitmap())
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(playPauseIcon, playPauseTitle, playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        androidx.media.session.MediaButtonReceiver.handleIntent(mediaSession, intent)

        when (intent?.action) {
            ACTION_TOGGLE_PLAY -> {
                if (isPlaying) {
                    onPauseAction?.invoke()
                    updatePlaybackState(false)
                } else {
                    onPlayAction?.invoke()
                    updatePlaybackState(true)
                }
            }
            ACTION_PLAY -> {
                onPlayAction?.invoke()
                updatePlaybackState(true)
            }
            ACTION_PAUSE -> {
                onPauseAction?.invoke()
                updatePlaybackState(false)
            }
            ACTION_NEXT -> onNextAction?.invoke()
            ACTION_PREVIOUS -> onPreviousAction?.invoke()
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved: User removed app from recents, terminating service")
        cleanupAndStop()
    }

    private fun cleanupAndStop() {
        try {
            serviceScope.cancel()
        } catch (_: Exception) {}

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        audioEngine?.release()
        audioEngine = null

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.cancel(NOTIFICATION_ID)

        stopSelf()
    }

    fun playNativeStream(url: String, title: String, artist: String, artworkUrl: String) {
        audioEngine?.playStream(url, title, artist, artworkUrl)
        updateMetadata(title, artist, "YouTube Music", artworkUrl)
        updatePlaybackState(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupAndStop()
    }

    companion object {
        private const val TAG = "MediaPlaybackService"
        const val CHANNEL_ID = "brave_music_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE_PLAY = "com.bravemusic.action.TOGGLE_PLAY"
        const val ACTION_PLAY = "com.bravemusic.action.PLAY"
        const val ACTION_PAUSE = "com.bravemusic.action.PAUSE"
        const val ACTION_NEXT = "com.bravemusic.action.NEXT"
        const val ACTION_PREVIOUS = "com.bravemusic.action.PREVIOUS"
    }
}
