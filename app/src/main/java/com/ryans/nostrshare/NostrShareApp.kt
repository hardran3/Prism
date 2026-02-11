package com.ryans.nostrshare

import android.app.Application
import okhttp3.OkHttpClient

class NostrShareApp : Application() {
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

    companion object {
        private lateinit var instance: NostrShareApp
        fun getInstance(): NostrShareApp = instance
    }
    
    init {
        instance = this
    }
}
