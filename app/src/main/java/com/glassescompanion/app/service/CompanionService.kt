package com.glassescompanion.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.glassescompanion.app.App
import com.glassescompanion.app.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps translation, recording and the BLE link alive while the screen is off. */
class CompanionService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Active session", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Glasses Companion")
            .setContentText("Session running")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val type = if (Build.VERSION.SDK_INT >= 30) {
            (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        runCatching { ServiceCompat.startForeground(this, 7, n, type) }.onFailure { stopSelf() }
        val graph = (application as App).graph
        lifecycleScope.launch {
            while (true) {
                delay(5_000)
                val busy = graph.dialogue.state.value.running || graph.interpretation.state.value.running ||
                    graph.recorder.state.value.recording || graph.call.state.value.active || graph.importer.state.value.phase.name in listOf("JOINING", "LISTING", "DOWNLOADING")
                if (!busy) {
                    stopSelf()
                    break
                }
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "session"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CompanionService::class.java)) }
        }
    }
}
