package dev.ghar.watch.sync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.ghar.watch.data.Prefs
import dev.ghar.watch.data.Rec
import dev.ghar.watch.data.Store
import dev.ghar.watch.health.Passive
import dev.ghar.watch.health.Sleep
import dev.ghar.watch.net.Supabase
import dev.ghar.watch.net.WifiNet
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Battery {
    data class State(val pct: Int, val charging: Boolean)

    fun read(ctx: Context): State? {
        val i: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return State(level * 100 / scale, plugged)
    }
}

/** Sends the outbox to Supabase over Wi-Fi. Nothing is deleted locally until the server returns 2xx. */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Store.init(ctx)
        if (!Prefs.configured) { Store.kvSet("last_error", "not set up – run push_config.ps1"); return Result.success() }
        val force = inputData.getBoolean("force", false)
        if (Store.pending() == 0 && !force) return Result.success()

        val lease = WifiNet.acquire(ctx) ?: run {
            Store.kvSet("last_error", "no Wi-Fi " + now())
            return Result.retry()
        }
        return try {
            val n = Supabase.uploadAll(lease.network, Battery.read(ctx)?.pct)
            Store.kvSet("last_upload", System.currentTimeMillis().toString())
            Store.kvSet("last_count", n.toString())
            Store.kvSet("last_error", "")
            Result.success()
        } catch (e: Supabase.AuthException) {
            Store.kvSet("last_error", "LOGIN: ${e.message}")
            Result.failure()                       // retrying won't help until re-setup
        } catch (e: Exception) {
            Store.kvSet("last_error", "${e.javaClass.simpleName}: ${e.message?.take(120)} " + now())
            Result.retry()
        } finally {
            lease.close()
        }
    }

    private fun now() = java.time.LocalTime.now().toString().take(5)
}

/** Every 30 min: battery log, open-sleep checkpoint, auto battery saver. Cheap, no network. */
class HeartbeatWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Store.init(ctx)
        val b = Battery.read(ctx)
        if (b != null) {
            Rec.sample("battery", System.currentTimeMillis(), b.pct.toDouble(), JSONObject().put("charging", b.charging))
            autoSaver(ctx, b)
        }
        Sleep.checkpoint()
        runCatching { dev.ghar.watch.system.WaterNotify.maybeRemind(ctx) }
        return Result.success()
    }

    private suspend fun autoSaver(ctx: Context, b: Battery.State) {
        if (!Prefs.autoSaver) return
        val saved = Store.kvGet("saver_prev")
        if (saved == null && b.pct < 20 && !b.charging && Prefs.powerMode != "ESSENTIAL") {
            Store.kvSet("saver_prev", Prefs.powerMode)
            Prefs.powerMode = "ESSENTIAL"
            Passive.register(ctx)
        } else if (saved != null && (b.charging || b.pct >= 40)) {
            Prefs.powerMode = saved
            Store.kvDel("saver_prev")
            Passive.register(ctx)
        }
    }
}

/** Re-registers the Health Services listener (after boot, setup, permission or mode changes). */
class RegisterWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Passive.register(applicationContext)
        return Result.success()
    }
}

object Scheduler {
    fun apply(ctx: Context) {
        Store.init(ctx)
        val wm = WorkManager.getInstance(ctx)
        val mode = Prefs.syncMode
        val hours = when (mode) { "H3" -> 3L; "H6" -> 6L; else -> 1L }
        val cons = Constraints.Builder().setRequiresCharging(mode == "CHARGING").build()
        val upload = PeriodicWorkRequestBuilder<UploadWorker>(hours, TimeUnit.HOURS)
            .setConstraints(cons)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork("upload", ExistingPeriodicWorkPolicy.UPDATE, upload)

        if (mode == "CHARGING") {
            // Fallback: if the watch never sits on the charger, still upload once a day.
            val daily = PeriodicWorkRequestBuilder<UploadWorker>(24, TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork("upload_daily", ExistingPeriodicWorkPolicy.UPDATE, daily)
        } else {
            wm.cancelUniqueWork("upload_daily")
        }

        val hb = PeriodicWorkRequestBuilder<HeartbeatWorker>(30, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork("heartbeat", ExistingPeriodicWorkPolicy.KEEP, hb)
    }

    fun syncNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf("force" to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("upload_now", ExistingWorkPolicy.REPLACE, req)
    }

    fun registerNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "register", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RegisterWorker>().build()
        )
    }
}
