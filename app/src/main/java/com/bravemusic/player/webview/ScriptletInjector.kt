package com.bravemusic.player.webview

import android.content.Context
import android.util.Base64
import android.webkit.WebView
import com.bravemusic.player.adblock.BraveShield
import java.io.InputStreamReader

object ScriptletInjector {

    fun injectAll(context: Context, webView: WebView) {
        injectScript(context, webView, "scripts/ad_skipper.js")
        injectAmoledTheme(context, webView)
        injectBraveCosmeticCss(webView)
        injectScript(context, webView, "scripts/background_audio.js")
    }

    private fun injectScript(context: Context, webView: WebView, assetPath: String) {
        try {
            val script = context.assets.open(assetPath).use { input ->
                InputStreamReader(input).readText()
            }
            webView.evaluateJavascript(script, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun injectAmoledTheme(context: Context, webView: WebView) {
        try {
            val css = context.assets.open("scripts/amoled_theme.css").use { input ->
                InputStreamReader(input).readText()
            }
            injectCss(webView, "brave-amoled-theme", css)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun injectBraveCosmeticCss(webView: WebView) {
        val cosmeticCss = BraveShield.getCosmeticCss()
        if (cosmeticCss.isNotEmpty()) {
            injectCss(webView, "brave-cosmetic-css", cosmeticCss)
        }
    }

    private fun injectCss(webView: WebView, styleId: String, css: String) {
        val encodedCss = Base64.encodeToString(css.toByteArray(), Base64.NO_WRAP)
        val js = """
            (function() {
                try {
                    var el = document.getElementById('$styleId');
                    if (el) {
                        el.textContent = window.atob('$encodedCss');
                    } else {
                        var style = document.createElement('style');
                        style.id = '$styleId';
                        style.type = 'text/css';
                        style.appendChild(document.createTextNode(window.atob('$encodedCss')));
                        (document.head || document.documentElement).appendChild(style);
                    }
                } catch (e) {
                    console.error('BraveMusic CSS injection failed', e);
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }
}
