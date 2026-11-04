package com.glassescompanion.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.glassescompanion.app.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Reminder(val id: Long, val title: String, val atMs: Long, val durationMin: Int = 30) {
    /** In-Progress until its time window ends, then Expired. */
    fun status(now: Long = System.currentTimeMillis()): String =
        if (now < atMs + durationMin * 60_000L) "In-Progress" else "Expired"
}

class ReminderStore(private val context: Context) {
    private val file = File(context.filesDir, "reminders.json")
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Reminder>> = _items.asStateFlow()

    fun add(title: String, atMs: Long): Reminder {
        val r = Reminder(System.currentTimeMillis(), title.ifBlank { "Reminder" }, atMs)
        _items.value = (_items.value + r).sortedBy { it.atMs }
        save()
        schedule(r)
        return r
    }

    fun delete(id: Long) {
        _items.value.firstOrNull { it.id == id }?.let { cancel(it) }
        _items.value = _items.value.filterNot { it.id == id }
        save()
    }

    fun rescheduleAll() = _items.value.filter { it.atMs > System.currentTimeMillis() }.forEach { schedule(it) }

    private fun pending(r: Reminder): PendingIntent = PendingIntent.getBroadcast(
        context, r.id.toInt(),
        Intent(context, ReminderReceiver::class.java).putExtra("title", r.title).putExtra("id", r.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun schedule(r: Reminder) {
        if (r.atMs <= System.currentTimeMillis()) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMs, pending(r))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMs, pending(r))
    }

    private fun cancel(r: Reminder) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(r))
    }

    private fun load(): List<Reminder> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Reminder(o.getLong("id"), o.getString("title"), o.getLong("atMs"), o.optInt("durationMin", 30))
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val arr = JSONArray()
        _items.value.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("atMs", it.atMs).put("durationMin", it.durationMin)) }
        file.writeText(arr.toString())
    }

    companion object {
        const val CHANNEL = "reminders"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderStore.ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val title = intent.getStringExtra("title") ?: "Reminder"
        val n = NotificationCompat.Builder(context, ReminderStore.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Reminder")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(intent.getLongExtra("id", 0).toInt(), n)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) (context.applicationContext as App).graph.reminders.rescheduleAll()
    }
}
