package com.bravemusic.player.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.bravemusic.player.network.BraveHttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NativeAudioEngine(private val context: Context) {

    private var exoPlayer: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isPlayingState = MutableStateFlow(false)
    val isPlayingState: StateFlow<Boolean> = _isPlayingState.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _currentTrackTitle = MutableStateFlow("")
    val currentTrackTitle: StateFlow<String> = _currentTrackTitle.asStateFlow()

    private val _currentTrackArtist = MutableStateFlow("")
    val currentTrackArtist: StateFlow<String> = _currentTrackArtist.asStateFlow()

    private val _currentArtworkUrl = MutableStateFlow("")
    val currentArtworkUrl: StateFlow<String> = _currentArtworkUrl.asStateFlow()

    var onTrackEnded: (() -> Unit)? = null

    private val progressUpdater = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    _currentPositionMs.value = player.currentPosition
                    _durationMs.value = player.duration.coerceAtLeast(0L)
                }
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    init {
        initExoPlayer()
    }

    private fun initExoPlayer() {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }

        val okHttpClient = BraveHttpClient.getClient(context)
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true) // Handles native system audio focus automatically
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true) // Pauses automatically when headphones are unplugged
            .build().apply {
                // Configure Audio Offload for maximum battery efficiency
                try {
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setAudioOffloadPreferences(
                            TrackSelectionParameters.AudioOffloadPreferences.Builder()
                                .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                                .setIsGaplessSupportRequired(true)
                                .build()
                        )
                        .build()
                } catch (e: Exception) {
                    Log.w(TAG, "Audio offload preferences fallback: ${e.message}")
                }

                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlayingState.value = isPlaying
                        if (isPlaying) {
                            mainHandler.post(progressUpdater)
                        } else {
                            mainHandler.removeCallbacks(progressUpdater)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                _durationMs.value = duration.coerceAtLeast(0L)
                            }
                            Player.STATE_ENDED -> {
                                onTrackEnded?.invoke()
                            }
                            else -> {}
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer playback error: ${error.message}", error)
                    }
                })
            }
    }

    fun playStream(url: String, title: String, artist: String, artworkUrl: String) {
        exoPlayer?.let { player ->
            _currentTrackTitle.value = title
            _currentTrackArtist.value = artist
            _currentArtworkUrl.value = artworkUrl

            val metadata = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(if (artworkUrl.isNotEmpty()) Uri.parse(artworkUrl) else null)
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(metadata)
                .build()

            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
            Log.d(TAG, "ExoPlayer started streaming: $title by $artist")
        }
    }

    fun play() {
        exoPlayer?.play()
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun togglePlayPause() {
        exoPlayer?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    fun release() {
        mainHandler.removeCallbacks(progressUpdater)
        exoPlayer?.release()
        exoPlayer = null
    }

    companion object {
        private const val TAG = "NativeAudioEngine"
    }
}
