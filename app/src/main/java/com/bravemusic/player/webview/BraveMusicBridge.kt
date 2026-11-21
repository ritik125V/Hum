package com.bravemusic.player.webview

import android.webkit.JavascriptInterface

class BraveMusicBridge(
    private val onTrackChangedCallback: (title: String, artist: String, album: String, artworkUrl: String) -> Unit,
    private val onPlaybackStateCallback: (isPlaying: Boolean) -> Unit,
    private val onPlayerStateCallback: ((isExpanded: Boolean) -> Unit)? = null,
    private val showKeyboardCallback: (() -> Unit)? = null,
    private val hideKeyboardCallback: (() -> Unit)? = null,
    private val isAudioOnlyCallback: (() -> Boolean)? = null
) {
    @JavascriptInterface
    fun onTrackChanged(title: String, artist: String, album: String, artworkUrl: String) {
        onTrackChangedCallback(title, artist, album, artworkUrl)
    }

    @JavascriptInterface
    fun onPlaybackState(isPlaying: Boolean) {
        onPlaybackStateCallback(isPlaying)
    }

    @JavascriptInterface
    fun onPlaybackStateChanged(isPlaying: Boolean) {
        onPlaybackStateCallback(isPlaying)
    }

    @JavascriptInterface
    fun onPlayerState(isExpanded: Boolean) {
        onPlayerStateCallback?.invoke(isExpanded)
    }

    @JavascriptInterface
    fun onPlayerStateChanged(isExpanded: Boolean) {
        onPlayerStateCallback?.invoke(isExpanded)
    }

    @JavascriptInterface
    fun showKeyboard() {
        showKeyboardCallback?.invoke()
    }

    @JavascriptInterface
    fun hideKeyboard() {
        hideKeyboardCallback?.invoke()
    }

    @JavascriptInterface
    fun isAudioOnly(): Boolean {
        return isAudioOnlyCallback?.invoke() ?: true
    }
}
