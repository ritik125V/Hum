package com.bravemusic.player.network

import android.content.Context
import com.bravemusic.player.adblock.BraveShieldNetworkInterceptor
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

object BraveHttpClient {

    private var clientInstance: OkHttpClient? = null

    @Synchronized
    fun getClient(context: Context): OkHttpClient {
        return clientInstance ?: synchronized(this) {
            val cacheDir = File(context.cacheDir, "brave_http_cache")
            val cacheSize = 50L * 1024L * 1024L // 50 MB HTTP Cache
            val cache = Cache(cacheDir, cacheSize)

            val client = OkHttpClient.Builder()
                .cache(cache)
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(BraveShieldNetworkInterceptor())
                .addNetworkInterceptor { chain ->
                    // Normalize User-Agent to clean Chrome/Brave Android
                    val original = chain.request()
                    val req = original.newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                        .build()
                    chain.proceed(req)
                }
                .build()

            clientInstance = client
            client
        }
    }
}
