package dev.ghar.watch.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * Crash-safe local store (SQLite, WAL).
 *  outbox: every row waiting for upload, keyed by (table, row id). Re-writing the same id replaces it.
 *          `ver` changes on every write, so an upload only deletes the exact version it sent.
 *  dead:   rows the server rejected as invalid (kept, never silently dropped).
 *  kv:     small key-value state (today's totals, last HR, sync status, open sleep session).
 */
object Store {
    private lateinit var h: Helper

    fun init(c: Context) {
        if (!::h.isInitialized) {
            h = Helper(c.applicationContext)
            Prefs.init(c)
        }
    }

    private class Helper(c: Context) : SQLiteOpenHelper(c, "ghar.db", null, 1) {
        init { setWriteAheadLoggingEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("create table outbox(tbl text not null, rid text not null, json text not null, ver integer not null, primary key(tbl, rid))")
            db.execSQL("create table dead(tbl text not null, rid text not null, json text not null, err text, at integer not null)")
            db.execSQL("create table kv(k text primary key, v text)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    data class Row(val rid: String, val json: String, val ver: Long)

    @Synchronized
    fun enqueue(tbl: String, rid: String, json: JSONObject) {
        val cv = ContentValues()
        cv.put("tbl", tbl); cv.put("rid", rid); cv.put("json", json.toString()); cv.put("ver", System.nanoTime())
        h.writableDatabase.insertWithOnConflict("outbox", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun batch(tbl: String, limit: Int): List<Row> {
        val out = ArrayList<Row>()
        h.readableDatabase.rawQuery(
            "select rid, json, ver from outbox where tbl = ? order by ver limit $limit", arrayOf(tbl)
        ).use { c -> while (c.moveToNext()) out.add(Row(c.getString(0), c.getString(1), c.getLong(2))) }
        return out
    }

    /** Deletes only rows whose version is unchanged since they were read (no lost updates). */
    @Synchronized
    fun confirm(tbl: String, rows: List<Row>) {
        val db = h.writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { db.delete("outbox", "tbl = ? and rid = ? and ver = ?", arrayOf(tbl, it.rid, it.ver.toString())) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun moveToDead(tbl: String, row: Row, err: String) {
        val db = h.writableDatabase
        val cv = ContentValues()
        cv.put("tbl", tbl); cv.put("rid", row.rid); cv.put("json", row.json); cv.put("err", err.take(500)); cv.put("at", System.currentTimeMillis())
        db.insert("dead", null, cv)
        db.delete("outbox", "tbl = ? and rid = ? and ver = ?", arrayOf(tbl, row.rid, row.ver.toString()))
    }

    @Synchronized
    fun pending(): Int = h.readableDatabase.rawQuery("select count(*) from outbox", null).use { c -> c.moveToFirst(); c.getInt(0) }

    @Synchronized
    fun deadCount(): Int = h.readableDatabase.rawQuery("select count(*) from dead", null).use { c -> c.moveToFirst(); c.getInt(0) }

    /** Puts server-rejected rows back in the outbox (e.g. after the SQL migration that added the missing column). */
    @Synchronized
    fun reviveDead(): Int {
        val db = h.writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            db.rawQuery("select tbl, rid, json from dead", null).use { c ->
                while (c.moveToNext()) {
                    val cv = ContentValues()
                    cv.put("tbl", c.getString(0)); cv.put("rid", c.getString(1)); cv.put("json", c.getString(2)); cv.put("ver", System.nanoTime())
                    db.insertWithOnConflict("outbox", null, cv, SQLiteDatabase.CONFLICT_IGNORE); n++
                }
            }
            db.delete("dead", null, null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return n
    }

    @Synchronized
    fun kvGet(k: String): String? =
        h.readableDatabase.rawQuery("select v from kv where k = ?", arrayOf(k)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    @Synchronized
    fun kvSet(k: String, v: String) {
        val cv = ContentValues(); cv.put("k", k); cv.put("v", v)
        h.writableDatabase.insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun kvDel(k: String) { h.writableDatabase.delete("kv", "k = ?", arrayOf(k)) }
}
