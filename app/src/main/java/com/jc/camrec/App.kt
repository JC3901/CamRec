package com.jc.camrec

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.jc.camrec.net.Http
import com.jc.camrec.rec.Recorder

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        ctx = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "Recording started / finished", NotificationManager.IMPORTANCE_DEFAULT)
        )
        Recorder.recoverLeftovers()
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { Http.client }
            .crossfade(true)
            .build()

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_EVENTS = "events"
        lateinit var ctx: Context
            private set
    }
}
