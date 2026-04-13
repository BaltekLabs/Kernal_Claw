package com.balteklabs.voiceos

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.CallLog
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Log
import com.google.gson.Gson

/**
 * DiscoveryEngine.kt — scans on-device data sources and populates the ContextStore.
 *
 * Runs a full scan once on first agent activation (or on explicit trigger), then
 * supports fast incremental updates as new notifications or messages arrive.
 *
 * Scanned sources
 * ───────────────
 *   contacts      — device contacts with recent interaction summary
 *   sms_thread    — one doc per SMS thread, summarising last 5 messages
 *   notification  — current notification cache (from VoiceOSNotificationService)
 *   calendar      — upcoming 14 days of events
 *   task          — all active tasks from SharedPrefs
 *   note          — all agent memory notes from SharedPrefs
 *   app           — installed launchable apps (for context-aware suggestions)
 *
 * Weights
 * ───────
 *   Unread SMS thread from a known contact → 2.5
 *   Notification < 10 min old             → 2.0
 *   Missed call < 1 h old                 → 2.5
 *   Calendar event starting < 2 h         → 2.0
 *   High-priority task                    → 2.0
 *   Everything else                       → 1.0
 */
class DiscoveryEngine(
    private val context: Context,
    private val store: ContextStore
) {
    companion object {
        private const val TAG = "VoiceOSDiscovery"
        private const val PREFS_NAME = "voiceos"
        /** Minimum ms between automatic full re-scans. */
        private const val RESCAN_INTERVAL_MS = 15 * 60_000L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    @Volatile private var scanning = false

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Run a full scan if [RESCAN_INTERVAL_MS] has elapsed since the last scan
     * or if [force] is true. Non-blocking — runs on a daemon thread.
     * Calls [onDone] when complete (on that same background thread).
     */
    fun scanIfStale(force: Boolean = false, onDone: ((docCount: Int) -> Unit)? = null) {
        if (scanning) return
        val lastScan = store.lastScanMs()
        val stale = force || (System.currentTimeMillis() - lastScan > RESCAN_INTERVAL_MS)
        if (!stale) {
            onDone?.invoke(store.totalDocCount())
            return
        }
        Thread {
            scanning = true
            try {
                val count = fullScan()
                Log.i(TAG, "Full scan complete — $count documents indexed")
                onDone?.invoke(count)
            } catch (e: Exception) {
                Log.e(TAG, "Full scan failed: ${e.message}")
            } finally {
                scanning = false
            }
        }.apply { isDaemon = true; name = "voiceos-discovery"; start() }
    }

    /** Synchronous full scan. Returns total doc count. */
    fun fullScan(): Int {
        scanContacts()
        scanSmsThreads()
        scanNotifications()
        scanCalendar()
        scanTasks()
        scanNotes()
        scanApps()
        return store.totalDocCount()
    }

    /** Push a single notification into the store incrementally (no full scan). */
    fun indexNotification(pkg: String, appName: String, title: String, text: String, timeMs: Long) {
        val sourceId = "$pkg:${title.take(40)}:$timeMs"
        val age = System.currentTimeMillis() - timeMs
        val weight = if (age < 10 * 60_000L) 2.0 else 1.0
        store.upsert(
            type      = ContextStore.TYPE_NOTIFICATION,
            sourceId  = sourceId,
            title     = "$appName${if (title.isNotBlank()) ": $title" else ""}",
            body      = text.take(300),
            timestamp = timeMs,
            tags      = "notification $appName",
            weight    = weight
        )
    }

    /** Remove a dismissed notification from the store. */
    fun removeNotification(pkg: String, title: String) {
        // Best-effort prefix match on source_id
        try {
            store.writableDatabase.delete(
                "context_docs",
                "type='notification' AND source_id LIKE ?",
                arrayOf("$pkg:${title.take(40)}%")
            )
        } catch (_: Exception) {}
    }

    // ── Internal scanners ──────────────────────────────────────────────────

    private fun scanContacts() {
        Log.d(TAG, "Scanning contacts…")
        store.clearType(ContextStore.TYPE_CONTACT)
        try {
            val cur = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            ) ?: return

            val seen = mutableSetOf<String>()
            cur.use { c ->
                while (c.moveToNext()) {
                    val name   = c.getString(0)?.trim()?.takeIf { it.isNotBlank() } ?: continue
                    val number = c.getString(1)?.trim() ?: continue
                    if (name.lowercase() in seen) continue
                    seen += name.lowercase()

                    // Build a short body with last-contact info
                    val daysSince  = daysSinceLastByNumber(number)
                    val bodyParts  = mutableListOf("Phone: $number")
                    if (daysSince != null) bodyParts += "Last contact: ${daysSince}d ago"

                    // Pull relationship notes if available
                    val relMeta = loadRelationships()[name.lowercase()]
                    relMeta?.let { meta ->
                        (meta["type"] as? String)?.let { bodyParts += "Relationship: $it" }
                        val notes = (meta["notes"] as? String)?.lines()?.lastOrNull()
                        if (!notes.isNullOrBlank()) bodyParts += "Last note: ${notes.take(80)}"
                    }

                    val weight = when {
                        daysSince != null && daysSince > 30 -> 1.5
                        else -> 1.0
                    }
                    store.upsert(
                        type      = ContextStore.TYPE_CONTACT,
                        sourceId  = number,
                        title     = name,
                        body      = bodyParts.joinToString(" | "),
                        timestamp = System.currentTimeMillis(),
                        tags      = "contact person ${relMeta?.get("type") ?: ""}".trim(),
                        weight    = weight
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact scan failed: ${e.message}")
        }
        store.markScanned(ContextStore.TYPE_CONTACT)
    }

    private fun scanSmsThreads() {
        Log.d(TAG, "Scanning SMS threads…")
        store.clearType(ContextStore.TYPE_SMS_THREAD)
        try {
            // Group SMS by thread_id
            val cur = context.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("thread_id", "address", "body", "date", "read", "type"),
                null, null, "date DESC"
            ) ?: return

            data class Msg(val address: String, val body: String, val date: Long, val read: Boolean, val outgoing: Boolean)
            val threads = mutableMapOf<String, MutableList<Msg>>()
            val threadAddress = mutableMapOf<String, String>()

            cur.use { c ->
                while (c.moveToNext()) {
                    val tid     = c.getString(0) ?: continue
                    val addr    = c.getString(1) ?: continue
                    val body    = c.getString(2) ?: ""
                    val date    = c.getLong(3)
                    val read    = c.getInt(4) == 1
                    val outgoing = c.getInt(5) == 2  // SMS type 2 = sent

                    threads.getOrPut(tid) { mutableListOf() }
                        .add(Msg(addr, body, date, read, outgoing))
                    if (tid !in threadAddress) threadAddress[tid] = addr
                }
            }

            for ((tid, msgs) in threads) {
                val addr     = threadAddress[tid] ?: continue
                val name     = resolveContactName(addr)
                val recent   = msgs.take(5)
                val unreadCount = msgs.count { !it.read && !it.outgoing }
                val lastTs   = msgs.firstOrNull()?.date ?: 0L

                val bodyLines = recent.map { m ->
                    val dir = if (m.outgoing) "you" else "them"
                    val age = ageString(m.date)
                    "[$age, $dir] ${m.body.take(100)}"
                }

                val weight = when {
                    unreadCount > 0 && (System.currentTimeMillis() - lastTs) < 24 * 3_600_000L -> 2.5
                    (System.currentTimeMillis() - lastTs) < 3 * 3_600_000L -> 1.5
                    else -> 1.0
                }

                store.upsert(
                    type      = ContextStore.TYPE_SMS_THREAD,
                    sourceId  = tid,
                    title     = "SMS: $name${if (unreadCount > 0) " ($unreadCount unread)" else ""}",
                    body      = bodyLines.joinToString("\n"),
                    timestamp = lastTs,
                    tags      = "sms message $name${if (unreadCount > 0) " unread" else ""}",
                    weight    = weight
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "SMS scan failed: ${e.message}")
        }
        store.markScanned(ContextStore.TYPE_SMS_THREAD)
    }

    private fun scanNotifications() {
        Log.d(TAG, "Scanning notifications…")
        store.clearType(ContextStore.TYPE_NOTIFICATION)
        val notifs = VoiceOSNotificationService.getRecent()
        for (n in notifs) {
            val pkg    = n["pkg"] as? String ?: continue
            val app    = n["app"] as? String ?: pkg
            val title  = n["title"] as? String ?: ""
            val text   = n["text"]  as? String ?: ""
            val timeMs = n["time"]  as? Long ?: System.currentTimeMillis()
            indexNotification(pkg, app, title, text, timeMs)
        }
        store.markScanned(ContextStore.TYPE_NOTIFICATION)
    }

    private fun scanCalendar() {
        Log.d(TAG, "Scanning calendar…")
        store.clearType(ContextStore.TYPE_CALENDAR)
        try {
            val now     = System.currentTimeMillis()
            val twoWeeks = now + 14L * 86_400_000L
            val cur = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.EVENT_LOCATION,
                    CalendarContract.Events.DESCRIPTION
                ),
                "${CalendarContract.Events.DTSTART} BETWEEN ? AND ?",
                arrayOf(now.toString(), twoWeeks.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            ) ?: return

            val sdf = java.text.SimpleDateFormat("EEE MMM d, HH:mm", java.util.Locale.US)
            cur.use { c ->
                while (c.moveToNext()) {
                    val eventId  = c.getString(0)
                    val title    = c.getString(1) ?: "Untitled"
                    val start    = c.getLong(2)
                    val end      = c.getLong(3)
                    val location = c.getString(4) ?: ""
                    val desc     = c.getString(5) ?: ""

                    val durMin  = ((end - start) / 60_000L).toInt()
                    val bodyParts = mutableListOf("When: ${sdf.format(java.util.Date(start))}")
                    if (durMin > 0) bodyParts += "Duration: ${durMin}min"
                    if (location.isNotBlank()) bodyParts += "Location: $location"
                    if (desc.isNotBlank()) bodyParts += "Notes: ${desc.take(120)}"

                    val minsUntil = (start - now) / 60_000L
                    val weight = when {
                        minsUntil < 120 -> 2.0
                        minsUntil < 1440 -> 1.5
                        else -> 1.0
                    }

                    store.upsert(
                        type      = ContextStore.TYPE_CALENDAR,
                        sourceId  = eventId,
                        title     = title,
                        body      = bodyParts.joinToString(" | "),
                        timestamp = start,
                        tags      = "calendar event${if (location.isNotBlank()) " $location" else ""}",
                        weight    = weight
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Calendar scan failed: ${e.message}")
        }
        store.markScanned(ContextStore.TYPE_CALENDAR)
    }

    private fun scanTasks() {
        Log.d(TAG, "Scanning tasks…")
        store.clearType(ContextStore.TYPE_TASK)
        try {
            val json  = prefs.getString("agent_tasks", "[]") ?: "[]"
            @Suppress("UNCHECKED_CAST")
            val tasks = (gson.fromJson(json, List::class.java) as List<Map<String, Any>>)
            for (task in tasks) {
                val id       = task["id"] as? String ?: continue
                val title    = task["title"] as? String ?: continue
                val status   = task["status"] as? String ?: "pending"
                val priority = task["priority"] as? String ?: "medium"
                val notes    = task["notes"] as? String ?: ""

                val weight = when {
                    status == "done" -> 0.5
                    priority == "high" -> 2.0
                    else -> 1.0
                }

                store.upsert(
                    type      = ContextStore.TYPE_TASK,
                    sourceId  = id,
                    title     = title,
                    body      = buildString {
                        append("Status: $status | Priority: $priority")
                        if (notes.isNotBlank()) append(" | Notes: ${notes.take(150)}")
                    },
                    timestamp = System.currentTimeMillis(),
                    tags      = "task $priority $status",
                    weight    = weight
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Task scan failed: ${e.message}")
        }
        store.markScanned(ContextStore.TYPE_TASK)
    }

    private fun scanNotes() {
        Log.d(TAG, "Scanning notes…")
        store.clearType(ContextStore.TYPE_NOTE)
        val raw = prefs.getString("agent_notes", "") ?: ""
        if (raw.isBlank()) {
            store.markScanned(ContextStore.TYPE_NOTE)
            return
        }
        // Each line is "[MM/dd HH:mm] text" or plain text
        val lines = raw.lines().filter { it.isNotBlank() }
        lines.forEachIndexed { idx, line ->
            val ts = parseDatePrefix(line) ?: System.currentTimeMillis()
            store.upsert(
                type      = ContextStore.TYPE_NOTE,
                sourceId  = "note_$idx",
                title     = "Note",
                body      = line.take(300),
                timestamp = ts,
                tags      = "note memory",
                weight    = 1.0
            )
        }
        store.markScanned(ContextStore.TYPE_NOTE)
    }

    private fun scanApps() {
        Log.d(TAG, "Scanning installed apps…")
        store.clearType(ContextStore.TYPE_APP)
        try {
            val pm   = context.packageManager
            val main = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
                .apply { addCategory(android.content.Intent.CATEGORY_LAUNCHER) }
            val apps = pm.queryIntentActivities(main, android.content.pm.PackageManager.GET_META_DATA)
            for (ri in apps) {
                val label = ri.loadLabel(pm).toString()
                val pkg   = ri.activityInfo.packageName
                store.upsert(
                    type      = ContextStore.TYPE_APP,
                    sourceId  = pkg,
                    title     = label,
                    body      = "Package: $pkg",
                    timestamp = System.currentTimeMillis(),
                    tags      = "app $label",
                    weight    = 1.0
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "App scan failed: ${e.message}")
        }
        store.markScanned(ContextStore.TYPE_APP)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun daysSinceLastByNumber(number: String): Int? {
        // Check call log
        var last: Long? = null
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.DATE),
                "${CallLog.Calls.NUMBER} LIKE ?",
                arrayOf("%${number.takeLast(9)}%"),
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                if (c.moveToFirst()) last = c.getLong(0)
            }
        } catch (_: Exception) {}
        // Also check SMS
        try {
            context.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("date"),
                "address LIKE ?",
                arrayOf("%${number.takeLast(9)}%"),
                "date DESC"
            )?.use { c ->
                if (c.moveToFirst()) {
                    val smsDate = c.getLong(0)
                    if (last == null || smsDate > last!!) last = smsDate
                }
            }
        } catch (_: Exception) {}
        return last?.let { ((System.currentTimeMillis() - it) / 86_400_000L).toInt() }
    }

    private fun resolveContactName(number: String): String {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            var name: String? = null
            context.contentResolver.query(uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
            name ?: number
        } catch (_: Exception) { number }
    }

    private fun loadRelationships(): Map<String, Map<String, Any>> {
        val json = prefs.getString("crm_relationships", "{}") ?: "{}"
        return try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(json, Map::class.java) as Map<String, Map<String, Any>>
        } catch (_: Exception) { emptyMap() }
    }

    private fun ageString(ms: Long): String {
        val age = System.currentTimeMillis() - ms
        return when {
            age < 60_000     -> "just now"
            age < 3_600_000  -> "${age / 60_000}m ago"
            age < 86_400_000 -> "${age / 3_600_000}h ago"
            else             -> "${age / 86_400_000}d ago"
        }
    }

    private val DATE_PREFIX_RE = Regex("""^\[(\d{2}/\d{2} \d{2}:\d{2})]""")
    private fun parseDatePrefix(line: String): Long? {
        val m = DATE_PREFIX_RE.find(line) ?: return null
        return try {
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            java.text.SimpleDateFormat("MM/dd/yy HH:mm", java.util.Locale.US)
                .parse("${m.groupValues[1].take(5)}/${"$year".takeLast(2)} ${m.groupValues[1].takeLast(5)}")
                ?.time
        } catch (_: Exception) { null }
    }
}
