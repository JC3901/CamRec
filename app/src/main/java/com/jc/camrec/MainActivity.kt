package com.jc.camrec

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.jc.camrec.ui.AppRoot
import com.jc.camrec.data.Store
import com.jc.camrec.rec.LiveAlerts
import com.jc.camrec.rec.RecorderService
import com.jc.camrec.ui.CamRecTheme
import com.jc.camrec.ui.DeepLink

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        // Make sure followed rooms are being watched whenever the app is opened.
        if (Store.settings.value.monitor && Store.follows.value.isNotEmpty()) RecorderService.ensureRunning(this)
        handleIntent(intent)
        setContent {
            CamRecTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) { AppRoot() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val site = intent?.getStringExtra(LiveAlerts.EXTRA_SITE) ?: return
        val username = intent.getStringExtra(LiveAlerts.EXTRA_USERNAME) ?: return
        DeepLink.room.value = site to username
        intent.removeExtra(LiveAlerts.EXTRA_SITE)
    }
}
