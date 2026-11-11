package dev.ghar.watch.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** Small settings + auth store. Tokens use commit() so a crash can't lose a rotated refresh token. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("ghar", Context.MODE_PRIVATE)
    }

    private fun str(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun putStr(k: String, v: String) { sp.edit().putString(k, v).commit() }

    var url: String
        get() = str("url")
        set(v) = putStr("url", v)
    var apiKey: String
        get() = str("key")
        set(v) = putStr("key", v)
    var person: String
        get() = str("person", "saurabh")
        set(v) = putStr("person", v)

    var refreshToken: String
        get() = str("refresh")
        set(v) = putStr("refresh", v)
    var accessToken: String
        get() = str("access")
        set(v) = putStr("access", v)
    var accessExpMs: Long
        get() = sp.getLong("access_exp", 0L)
        set(v) { sp.edit().putLong("access_exp", v).commit() }

    /** FULL = steps, kcal, floors, distance, HR, sleep state. ESSENTIAL = steps, kcal, sleep state. */
    var powerMode: String
        get() = str("power", "FULL")
        set(v) = putStr("power", v)

    /** CHARGING (upload hourly while on charger + daily fallback), H1, H3, H6 */
    var syncMode: String
        get() = str("sync", "CHARGING")
        set(v) = putStr("sync", v)

    var autoSaver: Boolean
        get() = sp.getBoolean("auto_saver", true)
        set(v) { sp.edit().putBoolean("auto_saver", v).commit() }

    // ---- Profile + daily goals (rings). Edited in Settings. ----
    var weightKg: Float
        get() = sp.getFloat("weight", 105f)
        set(v) { sp.edit().putFloat("weight", v).commit() }
    var heightCm: Int
        get() = sp.getInt("height", 185)
        set(v) { sp.edit().putInt("height", v).commit() }
    var age: Int
        get() = sp.getInt("age", 24)
        set(v) { sp.edit().putInt("age", v).commit() }
    var male: Boolean
        get() = sp.getBoolean("male", true)
        set(v) { sp.edit().putBoolean("male", v).commit() }
    var goalSteps: Int
        get() = sp.getInt("goal_steps", 10_000)
        set(v) { sp.edit().putInt("goal_steps", v).commit() }
    var goalMove: Int
        get() = sp.getInt("goal_move", 600)
        set(v) { sp.edit().putInt("goal_move", v).commit() }
    var goalFloors: Int
        get() = sp.getInt("goal_floors", 10)
        set(v) { sp.edit().putInt("goal_floors", v).commit() }

    var goalExercise: Int
        get() = sp.getInt("goal_ex", 30)
        set(v) { sp.edit().putInt("goal_ex", v).commit() }
    var goalHours: Int
        get() = sp.getInt("goal_hours", 12)
        set(v) { sp.edit().putInt("goal_hours", v).commit() }
    var goalWater: Int
        get() = sp.getInt("goal_water", 3500)
        set(v) { sp.edit().putInt("goal_water", v).commit() }
    var waterRemind: Boolean
        get() = sp.getBoolean("water_remind", true)
        set(v) { sp.edit().putBoolean("water_remind", v).commit() }

    /** Mifflin–St Jeor resting burn, kcal/day. */
    fun bmr(): Double {
        val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * age
        return if (male) base + 5 else base - 161
    }

    val deviceId: String
        get() {
            var id = str("device_id")
            if (id.isEmpty()) { id = "gw4-" + UUID.randomUUID().toString().take(8); putStr("device_id", id) }
            return id
        }

    val configured: Boolean get() = url.isNotEmpty() && apiKey.isNotEmpty() && refreshToken.isNotEmpty()

    fun saveTokens(access: String, refresh: String, expMs: Long) {
        sp.edit().putString("access", access).putString("refresh", refresh).putLong("access_exp", expMs).commit()
    }
}
