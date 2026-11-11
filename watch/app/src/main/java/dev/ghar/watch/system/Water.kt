package dev.ghar.watch.system

import android.annotation.SuppressLint
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
import dev.ghar.watch.R
import dev.ghar.watch.data.Day
import dev.ghar.watch.data.Prefs
import dev.ghar.watch.data.Rec
import dev.ghar.watch.data.Store
import dev.ghar.watch.presentation.MainActivity
import dev.ghar.watch.sync.Battery
import java.time.LocalTime

/**
 * Water reminders. Checked by the existing 30-min heartbeat (no extra alarms → no extra battery).
 * Fires at most every 90 min, 09:00–21:59, not while asleep or charging, and only until the goal is met.
 */
object WaterNotify {
    private const val CH = "water"
    private const val ID = 4201
    private const val GAP_MS = 90 * 60_000L
    const val PERM = "android.permission.POST_NOTIFICATIONS"

    fun allowed(ctx: Context) =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, PERM) == PackageManager.PERMISSION_GRANTED

    fun maybeRemind(ctx: Context) {
        if (!Prefs.waterRemind || !allowed(ctx)) return
        if (LocalTime.now().hour !in 9..21) return
        if (Store.kvGet("sleep_start") != null) return
        if (Battery.read(ctx)?.charging == true) return
        val ml = Day.waterMl(Rec.today())
        if (ml >= Prefs.goalWater) return
        val now = System.currentTimeMillis()
        val last = maxOf(Store.kvGet("water_last")?.toLongOrNull() ?: 0L, Store.kvGet("water_remind")?.toLongOrNull() ?: 0L)
        if (now - last < GAP_MS) return
        Store.kvSet("water_remind", now.toString())
        show(ctx, ml)
    }

    @SuppressLint("MissingPermission")   // checked in allowed() before calling
    private fun show(ctx: Context, ml: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Water reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val add = PendingIntent.getBroadcast(ctx, 1,
            Intent(ctx, WaterReceiver::class.java).putExtra("ml", 250),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_water)
            .setContentTitle("Drink water")
            .setContentText("%.2f / %.1f L today".format(ml / 1000.0, Prefs.goalWater / 1000.0))
            .setContentIntent(open)
            .addAction(0, "+250 ml", add)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID, n) }
    }

    fun cancel(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID)
}

/** "+250 ml" button on the reminder. Not exported: only our own PendingIntent can trigger it. */
class WaterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        Day.addWater(intent.getIntExtra("ml", 250))
        WaterNotify.cancel(context)
    }
}
