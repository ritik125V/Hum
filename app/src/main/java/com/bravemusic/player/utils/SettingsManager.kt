package com.bravemusic.player.utils

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isCustomMetadataEnabled: Boolean
        get() = prefs.getBoolean(KEY_CUSTOM_METADATA, false)
        set(value) = prefs.edit().putBoolean(KEY_CUSTOM_METADATA, value).apply()

    var customTitle: String
        get() = prefs.getString(KEY_CUSTOM_TITLE, "Focus Mode") ?: "Focus Mode"
        set(value) = prefs.edit().putString(KEY_CUSTOM_TITLE, value).apply()

    var customArtist: String
        get() = prefs.getString(KEY_CUSTOM_ARTIST, "Ambient Audio") ?: "Ambient Audio"
        set(value) = prefs.edit().putString(KEY_CUSTOM_ARTIST, value).apply()

    var artworkPreset: String
        get() = prefs.getString(KEY_ARTWORK_PRESET, "default") ?: "default"
        set(value) = prefs.edit().putString(KEY_ARTWORK_PRESET, value).apply()

    var isAudioOnlyEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_ONLY, value).apply()

    val isAmoledThemeEnabled: Boolean
        get() = true // permanently enabled for max battery saving

    var appIcon: String
        get() = prefs.getString(KEY_APP_ICON, "default") ?: "default"
        set(value) = prefs.edit().putString(KEY_APP_ICON, value).apply()

    var customLauncherName: String
        get() = prefs.getString(KEY_CUSTOM_LAUNCHER_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_LAUNCHER_NAME, value).apply()

    companion object {
        private const val PREFS_NAME = "brave_music_settings"
        private const val KEY_CUSTOM_METADATA = "key_custom_metadata"
        private const val KEY_CUSTOM_TITLE = "key_custom_title"
        private const val KEY_CUSTOM_ARTIST = "key_custom_artist"
        private const val KEY_ARTWORK_PRESET = "key_artwork_preset"
        private const val KEY_AUDIO_ONLY = "key_audio_only"
        private const val KEY_AMOLED_THEME = "key_amoled_theme"
        private const val KEY_APP_ICON = "key_app_icon"
        private const val KEY_CUSTOM_LAUNCHER_NAME = "key_custom_launcher_name"
    }
}
