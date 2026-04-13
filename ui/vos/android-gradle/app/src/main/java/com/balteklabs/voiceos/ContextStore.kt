package com.balteklabs.voiceos

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * ContextStore.kt — local FTS4-backed context database for the VoiceOS agent.
 *
 * Instead of re-scanning device APIs on every agent activation, discovered data
 * lives here and is queried semantically via SQLite FTS4 full-text search.
 *
 * Schema
 * ------
 *   context_docs   — primary record store (id, type, source_id, title, body, timestamp, tags)
 *   context_fts    — FTS4 shadow table over (title, body, tags) for keyword search
 *
 * Document types:  notification | sms_thread | contact | note | task | calendar | app
 *
 * Incremental updates: caller upserts individual docs by (type, source_id).
 * Full scan: DiscoveryEngine truncates + repopulates on demand.
 */
class ContextStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG        = "VoiceOSContext"
        private const val DB_NAME    = "voiceos_context.db"
        private const val DB_VERSION = 1

        // Document types
        const val TYPE_NOTIFICATION = "notification"
        const val TYPE_SMS_THREAD   = "sms_thread"
        const val TYPE_CONTACT      = "contact"
        const val TYPE_NOTE         = "note"
        const val TYPE_TASK         = "task"
        const val TYPE_CALENDAR     = "calendar"
        const val TYPE_APP          = "app"
    }

    // ── Schema ─────────────────────────────────────────────────────────────

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS context_docs (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                type      TEXT    NOT NULL,
                source_id TEXT,
                title     TEXT    DEFAULT '',
                body      TEXT    DEFAULT '',
                timestamp INTEGER DEFAULT 0,
                tags      TEXT    DEFAULT '',
                weight    REAL    DEFAULT 1.0
            )
        """.trimIndent())

        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS context_fts USING fts4(
                content="context_docs",
                title,
                body,
                tags,
                tokenize="unicode61"
            )
        """.trimIndent())

        // Trigger: keep FTS in sync on insert
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS ctx_fts_insert AFTER INSERT ON context_docs BEGIN
                INSERT INTO context_fts(rowid, title, body, tags)
                VALUES (new.id, new.title, new.body, new.tags);
            END
        """.trimIndent())

        // Trigger: keep FTS in sync on delete
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS ctx_fts_delete BEFORE DELETE ON context_docs BEGIN
                DELETE FROM context_fts WHERE rowid = old.id;
            END
        """.trimIndent())

        // Trigger: keep FTS in sync on update
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS ctx_fts_update BEFORE UPDATE ON context_docs BEGIN
                DELETE FROM context_fts WHERE rowid = old.id;
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS ctx_fts_update_after AFTER UPDATE ON context_docs BEGIN
                INSERT INTO context_fts(rowid, title, body, tags)
                VALUES (new.id, new.title, new.body, new.tags);
            END
        """.trimIndent())

        // Meta table: tracks when each type was last fully scanned
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS discovery_meta (
                type      TEXT PRIMARY KEY,
                scanned   INTEGER DEFAULT 0,
                doc_count INTEGER DEFAULT 0
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS context_docs")
        db.execSQL("DROP TABLE IF EXISTS context_fts")
        db.execSQL("DROP TABLE IF EXISTS discovery_meta")
        onCreate(db)
    }

    // ── Upsert helpers ─────────────────────────────────────────────────────

    /** Insert or replace a document identified by (type, source_id). */
    fun upsert(
        type: String,
        sourceId: String,
        title: String,
        body: String,
        timestamp: Long = System.currentTimeMillis(),
        tags: String = "",
        weight: Double = 1.0
    ): Long {
        val db = writableDatabase
        // Check if exists
        db.rawQuery(
            "SELECT id FROM context_docs WHERE type=? AND source_id=?",
            arrayOf(type, sourceId)
        ).use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                val cv = ContentValues().apply {
                    put("title",     title)
                    put("body",      body)
                    put("timestamp", timestamp)
                    put("tags",      tags)
                    put("weight",    weight)
                }
                db.update("context_docs", cv, "id=?", arrayOf(id.toString()))
                return id
            }
        }
        val cv = ContentValues().apply {
            put("type",      type)
            put("source_id", sourceId)
            put("title",     title)
            put("body",      body)
            put("timestamp", timestamp)
            put("tags",      tags)
            put("weight",    weight)
        }
        return db.insert("context_docs", null, cv)
    }

    /** Delete all documents of a given type — used before a full re-scan. */
    fun clearType(type: String) {
        writableDatabase.delete("context_docs", "type=?", arrayOf(type))
    }

    /** Delete a specific document by (type, source_id). */
    fun delete(type: String, sourceId: String) {
        writableDatabase.delete("context_docs", "type=? AND source_id=?", arrayOf(type, sourceId))
    }

    // ── Query helpers ──────────────────────────────────────────────────────

    data class ContextDoc(
        val id: Long,
        val type: String,
        val sourceId: String,
        val title: String,
        val body: String,
        val timestamp: Long,
        val tags: String,
        val weight: Double
    )

    /**
     * Full-text search across title, body, and tags.
     * Returns up to [limit] docs ranked by weight × recency.
     */
    fun search(query: String, typeFilter: String? = null, limit: Int = 10): List<ContextDoc> {
        if (query.isBlank()) return emptyList()
        // Sanitize query for FTS4: escape quotes, append wildcard on last token
        val sanitized = query.trim()
            .replace("\"", "")
            .split("\\s+".toRegex())
            .joinToString(" ") { "$it*" }

        val typeSql = if (typeFilter != null) "AND d.type = ?" else ""
        val args = if (typeFilter != null)
            arrayOf(sanitized, typeFilter)
        else
            arrayOf(sanitized)

        return try {
            readableDatabase.rawQuery("""
                SELECT d.id, d.type, d.source_id, d.title, d.body, d.timestamp, d.tags, d.weight
                FROM context_docs d
                JOIN context_fts f ON f.rowid = d.id
                WHERE context_fts MATCH ? $typeSql
                ORDER BY d.weight DESC, d.timestamp DESC
                LIMIT $limit
            """.trimIndent(), args).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(ContextDoc(
                            id        = c.getLong(0),
                            type      = c.getString(1),
                            sourceId  = c.getString(2) ?: "",
                            title     = c.getString(3) ?: "",
                            body      = c.getString(4) ?: "",
                            timestamp = c.getLong(5),
                            tags      = c.getString(6) ?: "",
                            weight    = c.getDouble(7)
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "FTS search failed for '$query': ${e.message}")
            emptyList()
        }
    }

    /** Get recent docs of a given type, sorted newest first. */
    fun getByType(type: String, limit: Int = 20): List<ContextDoc> {
        return readableDatabase.rawQuery("""
            SELECT id, type, source_id, title, body, timestamp, tags, weight
            FROM context_docs WHERE type=? ORDER BY timestamp DESC LIMIT $limit
        """.trimIndent(), arrayOf(type)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ContextDoc(
                        id        = c.getLong(0),
                        type      = c.getString(1),
                        sourceId  = c.getString(2) ?: "",
                        title     = c.getString(3) ?: "",
                        body      = c.getString(4) ?: "",
                        timestamp = c.getLong(5),
                        tags      = c.getString(6) ?: "",
                        weight    = c.getDouble(7)
                    ))
                }
            }
        }
    }

    /**
     * Aggregate "pending attention" items: unread notifications + recent SMS threads
     * that haven't been replied to + high-weight items from the last 24 h.
     */
    fun getPendingAttention(limit: Int = 8): List<ContextDoc> {
        val cutoff = System.currentTimeMillis() - 24 * 3_600_000L
        return readableDatabase.rawQuery("""
            SELECT id, type, source_id, title, body, timestamp, tags, weight
            FROM context_docs
            WHERE (
                (type IN ('notification','sms_thread') AND timestamp > ?)
                OR weight >= 2.0
            )
            ORDER BY weight DESC, timestamp DESC
            LIMIT $limit
        """.trimIndent(), arrayOf(cutoff.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ContextDoc(
                        id        = c.getLong(0),
                        type      = c.getString(1),
                        sourceId  = c.getString(2) ?: "",
                        title     = c.getString(3) ?: "",
                        body      = c.getString(4) ?: "",
                        timestamp = c.getLong(5),
                        tags      = c.getString(6) ?: "",
                        weight    = c.getDouble(7)
                    ))
                }
            }
        }
    }

    // ── Discovery meta ──────────────────────────────────────────────────────

    fun markScanned(type: String) {
        val count = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM context_docs WHERE type=?", arrayOf(type)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

        writableDatabase.execSQL("""
            INSERT OR REPLACE INTO discovery_meta(type, scanned, doc_count)
            VALUES (?, ?, ?)
        """.trimIndent(), arrayOf(type, System.currentTimeMillis(), count))
    }

    /** Returns map of type → {scanned_ms, doc_count} for status reporting. */
    fun getDiscoveryStatus(): Map<String, Map<String, Any>> {
        return readableDatabase.rawQuery(
            "SELECT type, scanned, doc_count FROM discovery_meta", null
        ).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    put(c.getString(0), mapOf(
                        "scanned_ms" to c.getLong(1),
                        "doc_count"  to c.getInt(2)
                    ))
                }
            }
        }
    }

    fun totalDocCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM context_docs", null
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun lastScanMs(): Long = readableDatabase.rawQuery(
        "SELECT MAX(scanned) FROM discovery_meta", null
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
}
