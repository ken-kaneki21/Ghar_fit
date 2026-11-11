package dev.ghar.watch.net

import android.net.Network
import dev.ghar.watch.data.Prefs
import dev.ghar.watch.data.Rec
import dev.ghar.watch.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Supabase client: refresh-token login + batched upserts over PostgREST.
 * One process-wide lock, because Supabase rotates refresh tokens and two parallel refreshes
 * with the same token can get the session revoked.
 */
object Supabase {
    class AuthException(msg: String) : Exception(msg)

    private val lock = Mutex()

    /** Parents first: samples/workouts/sleep reference devices. */
    private val ORDER = listOf("devices", "samples", "workouts", "sleep_sessions", "daily_summary", "labels")
    private const val BATCH = 300

    suspend fun uploadAll(net: Network, battery: Int?): Int = lock.withLock {
        withContext(Dispatchers.IO) {
            Rec.device(battery)
            var total = 0
            for (tbl in ORDER) {
                var rounds = 0
                while (rounds++ < 40) {
                    val rows = Store.batch(tbl, BATCH)
                    if (rows.isEmpty()) break
                    total += sendRows(net, tbl, rows)
                }
            }
            total
        }
    }

    private fun sendRows(net: Network, tbl: String, rows: List<Store.Row>): Int {
        val arr = JSONArray()
        rows.forEach { arr.put(JSONObject(it.json).put("person", Prefs.person)) }
        val (code, body) = upsert(net, tbl, arr)
        if (code in 200..299) { Store.confirm(tbl, rows); return rows.size }
        // Bad data (not auth / rate / server trouble): isolate the bad rows instead of blocking the queue forever.
        if (code in 400..499 && code != 401 && code != 403 && code != 408 && code != 429) {
            if (rows.size == 1) { Store.moveToDead(tbl, rows[0], "HTTP $code $body"); return 0 }
            var ok = 0
            rows.forEach { ok += sendRows(net, tbl, listOf(it)) }
            return ok
        }
        throw IOException("HTTP $code on $tbl: ${body.take(200)}")
    }

    private fun upsert(net: Network, tbl: String, arr: JSONArray): Pair<Int, String> {
        val url = "${Prefs.url}/rest/v1/$tbl"
        fun send(token: String) = http(net, "POST", url, mapOf(
            "apikey" to Prefs.apiKey,
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/json",
            "Prefer" to "resolution=merge-duplicates,return=minimal",
        ), arr.toString())
        var r = send(accessToken(net, force = false))
        if (r.first == 401) r = send(accessToken(net, force = true))
        return r
    }

    private fun accessToken(net: Network, force: Boolean): String {
        if (!force && Prefs.accessToken.isNotEmpty() && Prefs.accessExpMs > System.currentTimeMillis() + 60_000) {
            return Prefs.accessToken
        }
        val rt = Prefs.refreshToken
        if (rt.isEmpty()) throw AuthException("no refresh token – run push_config.ps1")
        val (code, body) = http(net, "POST", "${Prefs.url}/auth/v1/token?grant_type=refresh_token",
            mapOf("apikey" to Prefs.apiKey, "Content-Type" to "application/json"),
            JSONObject().put("refresh_token", rt).toString())
        if (code == 400 || code == 401 || code == 403) throw AuthException("refresh rejected ($code) – run push_config.ps1")
        if (code !in 200..299) throw IOException("token refresh HTTP $code")
        val j = JSONObject(body)
        val access = j.getString("access_token")
        val refresh = j.optString("refresh_token", rt)
        val expMs = System.currentTimeMillis() + j.optLong("expires_in", 3600L) * 1000L
        Prefs.saveTokens(access, refresh, expMs)   // committed before use
        return access
    }

    private fun http(net: Network, method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val c = net.openConnection(URL(url)) as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }
}
