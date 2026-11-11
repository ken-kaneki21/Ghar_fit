package dev.ghar.watch.system

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.ghar.watch.R
import dev.ghar.watch.data.Day
import dev.ghar.watch.data.Prefs
import dev.ghar.watch.data.Rec
import dev.ghar.watch.data.Store
import dev.ghar.watch.presentation.MainActivity
import dev.ghar.watch.sync.Battery
import dev.ghar.watch.workout.Workout
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Hourly move reminder (Apple's "time to stand"): at :50, if this hour has < 250 steps, buzz once.
 * Inexact alarm (no exact-alarm permission, Doze may shift it a few minutes). 09:00–21:59 only;
 * skipped while asleep, charging or in a workout. Tapping "Desk stretch" opens a 2-min guided stretch.
 */
object MoveReminder {
    private const val CH = "move"
    private const val ID = 4401

    private fun pi(ctx: Context) = PendingIntent.getBroadcast(ctx, 11, Intent(ctx, MoveReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Schedule the next :50. Safe to call repeatedly (replaces the pending alarm). */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        if (!Prefs.moveRemind) { am.cancel(pi(ctx)); return }
        val now = ZonedDateTime.now(Rec.zone)
        var next = now.truncatedTo(ChronoUnit.HOURS).plusMinutes(50)
        if (!next.isAfter(now.plusSeconds(30))) next = next.plusHours(1)
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pi(ctx)) }
    }

    fun check(ctx: Context) {
        try {
            if (!Prefs.moveRemind || !WaterNotify.allowed(ctx)) return
            val h = LocalTime.now().hour
            if (h !in 9..21) return
            if (Store.kvGet("sleep_start") != null) return
            if (Battery.read(ctx)?.charging == true) return
            if (Workout.state.value.active) return
            val steps = Day.hourly(Rec.today(), "steps")[h].toInt()
            if (steps >= Day.ACTIVE_HOUR_STEPS) return
            show(ctx, steps)
        } finally { schedule(ctx) }
    }

    @SuppressLint("MissingPermission")   // checked via WaterNotify.allowed()
    private fun show(ctx: Context, steps: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Move reminders", NotificationManager.IMPORTANCE_DEFAULT))
        fun open(route: String, code: Int) = PendingIntent.getActivity(ctx, code,
            Intent(ctx, MainActivity::class.java).putExtra("route", route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val need = (Day.ACTIVE_HOUR_STEPS.toInt() - steps).coerceAtLeast(0)
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_workout)
            .setContentTitle("Time to move")
            .setContentText("$need more steps this hour for the ring")
            .setContentIntent(open("home", 12))
            .addAction(0, "Desk stretch", open("stretch", 13))
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID, n) }
    }
}

class MoveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        MoveReminder.check(context)
    }
}
