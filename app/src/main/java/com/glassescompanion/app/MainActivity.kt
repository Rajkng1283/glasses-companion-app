package com.glassescompanion.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import com.glassescompanion.app.ui.AppNav
import com.glassescompanion.app.ui.CallInvite
import com.glassescompanion.app.ui.GlassesTheme

class MainActivity : ComponentActivity() {

    private val invite = mutableStateOf<CallInvite?>(null)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val g = (application as App).graph
        g.audio.probe()
        g.ble.connectLast()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as App).graph
        handleIntent(intent)
        setContent {
            GlassesTheme { AppNav(graph, invite.value) }
        }
        permissionLauncher.launch(requiredPermissions())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "glassescompanion" && data.host == "call") {
            val room = data.getQueryParameter("room") ?: return
            invite.value = CallInvite(room, data.getQueryParameter("server"))
        }
    }

    private fun requiredPermissions(): Array<String> {
        val p = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_PHONE_STATE,
        )
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_SCAN
            p += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            p += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            p += Manifest.permission.POST_NOTIFICATIONS
            p += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            p += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return p.distinct().toTypedArray()
    }
}
