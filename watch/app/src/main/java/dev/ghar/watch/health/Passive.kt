package dev.ghar.watch.health

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import dev.ghar.watch.data.Prefs
import dev.ghar.watch.data.Store

/** Registers the always-on Health Services listener. Safe to call repeatedly (it replaces the old one). */
object Passive {
    val HR_PERM: String get() = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else android.Manifest.permission.BODY_SENSORS
    val BG_PERM: String get() = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" else android.Manifest.permission.BODY_SENSORS_BACKGROUND
    const val ACT_PERM = android.Manifest.permission.ACTIVITY_RECOGNITION

    fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun dataTypes(ctx: Context): Set<DataType<*, *>> {
        val s = mutableSetOf<DataType<*, *>>(DataType.STEPS_DAILY, DataType.CALORIES_DAILY)
        if (Prefs.powerMode == "FULL") {
            s.add(DataType.FLOORS_DAILY)
            s.add(DataType.DISTANCE_DAILY)
            // Background HR needs both the HR and the background permission.
            if (has(ctx, HR_PERM) && has(ctx, BG_PERM)) s.add(DataType.HEART_RATE_BPM)
        }
        return s
    }

    suspend fun register(ctx: Context): String {
        Store.init(ctx)
        if (!has(ctx, ACT_PERM)) {
            Store.kvSet("passive_status", "needs Activity permission")
            return "needs permission"
        }
        return try {
            val types = dataTypes(ctx)
            val cfg = PassiveListenerConfig.builder()
                .setDataTypes(types)
                .setShouldUserActivityInfoBeRequested(true)
                .build()
            HealthServices.getClient(ctx).passiveMonitoringClient
                .setPassiveListenerServiceAsync(PassiveDataService::class.java, cfg)
                .await()
            val at = java.time.LocalTime.now().toString().take(5)
            val msg = "on: " + types.joinToString(", ") { it.name } + " · since $at"
            Store.kvSet("passive_status", msg)
            "ok"
        } catch (e: Exception) {
            val msg = "error: ${e.javaClass.simpleName}: ${e.message}"
            Store.kvSet("passive_status", msg)
            msg
        }
    }
}
