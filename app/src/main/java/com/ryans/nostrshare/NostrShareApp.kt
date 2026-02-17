package com.ryans.nostrshare

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import okhttp3.OkHttpClient

class NostrShareApp : Application(), ImageLoaderFactory {
    lateinit var client: OkHttpClient
        private set
    lateinit var database: com.ryans.nostrshare.data.DraftDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        client = OkHttpClient.Builder().build()
        database = com.ryans.nostrshare.data.DraftDatabase.getDatabase(this)
        com.ryans.nostrshare.utils.NotificationHelper.createNotificationChannel(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(VideoFrameDecoder.Factory())
            }
            .okHttpClient(client)
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(filesDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100MB Limit
                    .build()
            }
            .crossfade(true)
            .build()
    }

    companion object {
        private lateinit var instance: NostrShareApp
        fun getInstance(): NostrShareApp = instance
    }
    
    init {
        instance = this
    }
}
