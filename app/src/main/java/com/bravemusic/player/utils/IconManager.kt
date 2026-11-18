package com.bravemusic.player.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import com.bravemusic.player.MainActivity
import com.bravemusic.player.R

data class HumIconStyle(
    val id: String,
    val styleName: String,
    val previewRes: Int,
    val launcherRes: Int,
    val aliasName: String
)

object IconManager {
    private const val TAG = "IconManager"

    const val APP_NAME = "Hum"
    const val DEFAULT_STYLE_ID = "wave"

    val STYLES = listOf(
        HumIconStyle("wave", "Wave", R.drawable.ic_hum_preview_wave, R.mipmap.ic_launcher, "com.bravemusic.player.LauncherDefault"),
        HumIconStyle("blob", "Blob", R.drawable.ic_hum_preview_blob, R.mipmap.ic_launcher_blob, "com.bravemusic.player.LauncherBlob"),
        HumIconStyle("spark", "Spark", R.drawable.ic_hum_preview_spark, R.mipmap.ic_launcher_spark, "com.bravemusic.player.LauncherSpark"),
        HumIconStyle("grin", "Grin", R.drawable.ic_hum_preview_grin, R.mipmap.ic_launcher_grin, "com.bravemusic.player.LauncherGrin"),
        HumIconStyle("pop", "Pop", R.drawable.ic_hum_preview_pop, R.mipmap.ic_launcher_pop, "com.bravemusic.player.LauncherPop"),
        HumIconStyle("orbit", "Orbit", R.drawable.ic_hum_preview_orbit, R.mipmap.ic_launcher_orbit, "com.bravemusic.player.LauncherOrbit"),
        HumIconStyle("slide", "Slide", R.drawable.ic_hum_preview_slide, R.mipmap.ic_launcher_slide, "com.bravemusic.player.LauncherSlide"),
        HumIconStyle("split", "Split", R.drawable.ic_hum_preview_split, R.mipmap.ic_launcher_split, "com.bravemusic.player.LauncherSplit"),
        HumIconStyle("ripple", "Ripple", R.drawable.ic_hum_preview_ripple, R.mipmap.ic_launcher_ripple, "com.bravemusic.player.LauncherRipple"),
        HumIconStyle("stack", "Stack", R.drawable.ic_hum_preview_stack, R.mipmap.ic_launcher_stack, "com.bravemusic.player.LauncherStack"),
        HumIconStyle("luv", "Luv", R.drawable.ic_hum_preview_luv, R.mipmap.ic_launcher_luv, "com.bravemusic.player.LauncherLuv"),
        HumIconStyle("eye", "Eye", R.drawable.ic_hum_preview_eye, R.mipmap.ic_launcher_eye, "com.bravemusic.player.LauncherEye"),
        HumIconStyle("waves", "Waves", R.drawable.ic_hum_preview_waves, R.mipmap.ic_launcher_waves, "com.bravemusic.player.LauncherWaves"),
        HumIconStyle("pulse", "Pulse", R.drawable.ic_hum_preview_pulse, R.mipmap.ic_launcher_pulse, "com.bravemusic.player.LauncherPulse"),
        HumIconStyle("eclipse", "Eclipse", R.drawable.ic_hum_preview_eclipse, R.mipmap.ic_launcher_eclipse, "com.bravemusic.player.LauncherEclipse"),
        HumIconStyle("halo", "Halo", R.drawable.ic_hum_preview_halo, R.mipmap.ic_launcher_halo, "com.bravemusic.player.LauncherHalo"),
        HumIconStyle("cans", "Cans", R.drawable.ic_hum_preview_cans, R.mipmap.ic_launcher_cans, "com.bravemusic.player.LauncherCans"),
        HumIconStyle("note", "Note", R.drawable.ic_hum_preview_note, R.mipmap.ic_launcher_note, "com.bravemusic.player.LauncherNote"),
        HumIconStyle("dot", "Dot", R.drawable.ic_hum_preview_dot, R.mipmap.ic_launcher_dot, "com.bravemusic.player.LauncherDot"),
        HumIconStyle("bars", "Bars", R.drawable.ic_hum_preview_bars, R.mipmap.ic_launcher_bars, "com.bravemusic.player.LauncherBars"),
        HumIconStyle("loop", "Loop", R.drawable.ic_hum_preview_loop, R.mipmap.ic_launcher_loop, "com.bravemusic.player.LauncherLoop")
    )

    private val STYLE_BY_ID = STYLES.associateBy { it.id }

    private val ALL_ALIASES = STYLES.map { it.aliasName } + listOf(
        "com.bravemusic.player.LauncherBlob",
        "com.bravemusic.player.LauncherWave",
        "com.bravemusic.player.LauncherMasculine",
        "com.bravemusic.player.LauncherGenZ",
        "com.bravemusic.player.LauncherFeminine",
        "com.bravemusic.player.LauncherStealth"
    )

    fun normalizeKey(key: String): String {
        return when (key) {
            "default" -> "wave"
            "masculine" -> "orbit"
            "genz" -> "pulse"
            "feminine" -> "halo"
            "stealth" -> "loop"
            else -> if (STYLE_BY_ID.containsKey(key)) key else DEFAULT_STYLE_ID
        }
    }

    fun getStyle(key: String): HumIconStyle {
        return STYLE_BY_ID[normalizeKey(key)] ?: STYLES[0]
    }

    fun getStyleName(key: String): String {
        return getStyle(key).styleName
    }

    fun getIconRes(key: String): Int {
        return getStyle(key).launcherRes
    }

    fun getPreviewRes(key: String): Int {
        return getStyle(key).previewRes
    }

    fun applyAppIcon(context: Context, iconKey: String) {
        val pm = context.packageManager
        val packageName = context.packageName
        val style = getStyle(iconKey)
        val targetAlias = style.aliasName

        Log.d(TAG, "Switching Hum launcher icon to: ${style.styleName} ($targetAlias)")

        // 1. Enable chosen alias
        pm.setComponentEnabledSetting(
            ComponentName(packageName, targetAlias),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )

        // 2. Disable all other aliases
        for (comp in ALL_ALIASES) {
            if (comp != targetAlias) {
                pm.setComponentEnabledSetting(
                    ComponentName(packageName, comp),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }

    fun pinCustomShortcut(context: Context, iconKey: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sm = context.getSystemService(ShortcutManager::class.java)
            if (sm != null && sm.isRequestPinShortcutSupported) {
                val iconRes = getIconRes(iconKey)
                val intent = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val shortcut = ShortcutInfo.Builder(context, "hum_music_${System.currentTimeMillis()}")
                    .setShortLabel(APP_NAME)
                    .setLongLabel(APP_NAME)
                    .setIcon(Icon.createWithResource(context, iconRes))
                    .setIntent(intent)
                    .build()
                return sm.requestPinShortcut(shortcut, null)
            }
        }
        return false
    }
}
