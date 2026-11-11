package dev.ghar.watch.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.ghar.watch.data.Prefs
import dev.ghar.watch.data.Store
import dev.ghar.watch.sync.Scheduler

/** After a reboot or app update: re-register Health Services and the upload schedule. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        Scheduler.apply(context)
        Scheduler.registerNow(context)
    }
}

/**
 * Receives the Supabase URL, publishable key, person and refresh token from your laptop over ADB
 * (push_config.ps1). Protected by android.permission.DUMP, which only the ADB shell holds,
 * so no other app on the watch can change the config.
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        val url: String = intent.getStringExtra("url").orEmpty()
        val key: String = intent.getStringExtra("key").orEmpty()
        val refresh: String = intent.getStringExtra("refresh").orEmpty()
        val person: String = intent.getStringExtra("person") ?: "saurabh"
        if (url.isBlank() || key.isBlank() || refresh.isBlank()) {
            runCatching { resultData = "MISSING url/key/refresh" }
            return
        }
        Prefs.url = url.trim().trimEnd('/')
        Prefs.apiKey = key.trim()
        Prefs.person = person.trim()
        Prefs.saveTokens(access = "", refresh = refresh.trim(), expMs = 0L)
        Store.kvSet("last_error", "")
        Scheduler.apply(context)
        Scheduler.registerNow(context)
        Scheduler.syncNow(context)
        runCatching { resultData = "OK device=${Prefs.deviceId} person=${Prefs.person}" }
    }
}
