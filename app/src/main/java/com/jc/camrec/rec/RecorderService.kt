package com.jc.camrec.rec

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jc.camrec.App
import com.jc.camrec.MainActivity
import com.jc.camrec.R
import com.jc.camrec.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps the app alive while recording or watching followed rooms. */
class RecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var monitorJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground(buildNotification(Recorder.active.value.size, Store.settings.value.monitor, 0))
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CamRec:record").apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "CamRec:record")?.apply { setReferenceCounted(false); acquire() }

        scope.launch {
            combine(Recorder.active, Store.settings, Recorder.finalizing) { active, settings, finalizing ->
                Triple(active, settings, finalizing)
            }.collect { (active, settings, finalizing) ->
                if (settings.monitor && monitorJob?.isActive != true) startMonitor()
                if (!settings.monitor) { monitorJob?.cancel(); monitorJob = null }
                if (active.isEmpty() && !settings.monitor && finalizing == 0) {
                    stopSelf()
                } else {
                    try {
                        NotificationManagerCompat.from(this@RecorderService)
                            .notify(NOTIFICATION_ID, buildNotification(active.size, settings.monitor, finalizing, active.values.map { it.username }))
                    } catch (_: SecurityException) {}
                }
            }
        }
    }

    private fun startMonitor() {
        monitorJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try { Recorder.checkFollows(autoStart = true) } catch (e: Exception) { Log.w("Monitor", "check failed", e) }
                delay(Store.settings.value.pollSeconds.coerceAtLeast(20) * 1000L)
            }
        }
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(recording: Int, monitor: Boolean, finalizing: Int, names: List<String> = emptyList()): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when {
            recording > 0 -> "Recording $recording room" + if (recording > 1) "s" else ""
            finalizing > 0 -> "Saving recording…"
            else -> "Watching followed rooms"
        }
        val text = when {
            names.isNotEmpty() -> names.joinToString(", ")
            monitor -> "Auto-records followed rooms when they go live"
            else -> ""
        }
        val b = NotificationCompat.Builder(this, App.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (recording > 0) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, "Stop & save all", stop)
        }
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) Recorder.stopAll()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP_ALL = "com.jc.camrec.STOP_ALL"

        fun ensureRunning(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RecorderService::class.java))
            } catch (e: Exception) {
                Log.w("RecorderService", "Could not start the recording service", e)
            }
        }
    }
}

/** Restarts background monitoring after a reboot, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Store.settings.value.monitor) {
            RecorderService.ensureRunning(context)
        }
    }
}
