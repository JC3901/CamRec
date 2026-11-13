package com.jc.camrec.rec

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jc.camrec.App
import com.jc.camrec.MainActivity
import com.jc.camrec.R
import com.jc.camrec.data.Follow
import com.jc.camrec.sites.Site

/** Heads-up "room is live" notifications for followed rooms, with Watch / Stop / Record actions. */
object LiveAlerts {
    const val ACTION_STOP = "com.jc.camrec.ALERT_STOP"
    const val ACTION_RECORD = "com.jc.camrec.ALERT_RECORD"
    const val EXTRA_SITE = "site"
    const val EXTRA_USERNAME = "username"

    private fun id(key: String) = 2000 + (key.hashCode() and 0xFFFFF)

    /** A followed room went live and recording has started. */
    fun recording(site: Site, username: String) = post(
        site, username,
        title = "$username is live",
        text = "Recording started · ${site.name}",
        actionLabel = "Stop & save", action = ACTION_STOP,
    )

    /** A followed room went live but is not being recorded (auto off, or Wi-Fi only). */
    fun live(site: Site, username: String, why: String?) = post(
        site, username,
        title = "$username is live",
        text = why ?: "Now live on ${site.name}",
        actionLabel = "Record", action = ACTION_RECORD,
    )

    /** An automatic recording could not start. */
    fun failed(site: Site, username: String, reason: String) = post(
        site, username,
        title = "Couldn't record $username",
        text = reason,
        actionLabel = "Try again", action = ACTION_RECORD,
    )

    fun cancel(key: String) {
        try { NotificationManagerCompat.from(App.ctx).cancel(id(key)) } catch (_: Exception) {}
    }

    private fun post(site: Site, username: String, title: String, text: String, actionLabel: String, action: String) {
        val ctx = App.ctx
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val key = Follow.key(site.id, username)
        val requestCode = id(key)
        val watch = PendingIntent.getActivity(
            ctx, requestCode,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_SITE, site.id).putExtra(EXTRA_USERNAME, username),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val act = PendingIntent.getBroadcast(
            ctx, requestCode,
            Intent(ctx, AlertActionReceiver::class.java).setAction(action)
                .putExtra(EXTRA_SITE, site.id).putExtra(EXTRA_USERNAME, username),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, App.CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(watch).setAutoCancel(true)
            .addAction(0, "Watch", watch)
            .addAction(0, actionLabel, act)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(requestCode, n) } catch (_: SecurityException) {}
    }
}

/** Handles the Stop / Record buttons on live notifications. */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val siteId = intent.getStringExtra(LiveAlerts.EXTRA_SITE) ?: return
        val username = intent.getStringExtra(LiveAlerts.EXTRA_USERNAME) ?: return
        val key = Follow.key(siteId, username)
        when (intent.action) {
            LiveAlerts.ACTION_STOP -> { Recorder.stop(key); LiveAlerts.cancel(key) }
            LiveAlerts.ACTION_RECORD -> { LiveAlerts.cancel(key); Recorder.start(Site.byId(siteId), username, auto = true) }
        }
    }
}
