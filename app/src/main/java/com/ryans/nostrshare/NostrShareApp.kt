package com.ryans.nostrshare

import android.app.Application
import okhttp3.OkHttpClient

class NostrShareApp : Application() {
    lateinit var client: OkHttpClient
        private set

    override fun onCreate() {
        super.onCreate()
        client = OkHttpClient.Builder().build()
    }

    companion object {
        private lateinit var instance: NostrShareApp
        fun getInstance(): NostrShareApp = instance
    }
    
    init {
        instance = this
    }
}
