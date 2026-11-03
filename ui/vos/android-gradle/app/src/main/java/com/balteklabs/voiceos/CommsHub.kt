package com.balteklabs.voiceos

import android.app.Notification
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CommsHub — one recency-first view of everything people send you.
 *
 * The Android SMS provider only holds plain SMS. RCS chats (Google Messages),
 * MMS (group texts, pictures) and third-party chat apps never land there, so
 * tools that read only `content://sms` see an inbox that stopped months ago.
 *
 * Sources merged here, all filtered to a time window:
 *   - SMS            content://sms   (both directions, read flag)
 *   - MMS            content://mms   (group texts, pictures; text parts + sender)
 *   - Calls          CallLog         (incoming / outgoing / missed)
 *   - Chat messages  messaging notifications (RCS, WhatsApp, Signal, Telegram…),
 *                    persisted by [MessageLog] as they arrive. Only messages that
 *                    produced a notification while VoiceOS had notification access.
 */
class CommsHub(private val context: Context) {

    enum class Dir { IN, OUT, MISSED }

    data class Event(
        val time: Long,
        val person: String,          // display name (or number when unknown)
        val number: String?,         // phone number when known
        val channel: String,         // "SMS", "MMS", "Call", or the chat app's name
        val dir: Dir,
        val text: String,
        val unread: Boolean = false,
        val group: Boolean = false
    )

    companion object {
        private const val DAY = 86_400_000L

        /** Persistent log of chat-app messages captured from notifications. */
        @Volatile private var log: MessageLog? = null
        fun messageLog(context: Context): MessageLog =
            log ?: synchronized(this) { log ?: MessageLog(context.applicationContext).also { log = it } }

        fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
            val age = now - ms
            return when {
                age < 60_000      -> "just now"
                age < 3_600_000   -> "${age / 60_000}m ago"
                age < DAY         -> "${age / 3_600_000}h ago"
                age < 2 * DAY     -> "yesterday"
                else              -> "${age / DAY}d ago"
            }
        }

        fun stamp(ms: Long): String =
            SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).format(Date(ms))

        fun suffix(number: String?): String? =
            number?.filter { it.isDigit() }?.takeLast(9)?.takeIf { it.length >= 7 }
    }

    // ── Collection ─────────────────────────────────────────────────────────

    private val nameCache = HashMap<String, String>()

    private fun nameFor(number: String): String {
        val key = suffix(number) ?: number
        return nameCache.getOrPut(key) {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } catch (_: Exception) { null } ?: number
        }
    }

    /** All events since [sinceMs], newest first. */
    fun events(sinceMs: Long, max: Int = 1500): List<Event> {
        val out = ArrayList<Event>()
        out += smsEvents(sinceMs)
        out += mmsEvents(sinceMs)
        out += callEvents(sinceMs)
        out += dedupe(messageLog(context).since(sinceMs), out)
        return out.sortedByDescending { it.time }.take(max)
    }

    private fun smsEvents(sinceMs: Long): List<Event> {
        val list = ArrayList<Event>()
        try {
            context.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("address", "body", "date", "type", "read"),
                "date > ?", arrayOf(sinceMs.toString()), "date DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val addr = c.getString(0) ?: continue
                    val type = c.getInt(3)              // 1 = received, 2 = sent
                    if (type != 1 && type != 2) continue // skip drafts / failed / queued
                    val incoming = type == 1
                    list += Event(
                        time = c.getLong(2), person = nameFor(addr), number = addr,
                        channel = "SMS", dir = if (incoming) Dir.IN else Dir.OUT,
                        text = c.getString(1) ?: "",
                        unread = incoming && c.getInt(4) == 0
                    )
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun mmsEvents(sinceMs: Long): List<Event> {
        val list = ArrayList<Event>()
        try {
            // MMS dates are in seconds
            context.contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id", "date", "msg_box", "read"),
                "date > ?", arrayOf((sinceMs / 1000).toString()), "date DESC"
            )?.use { c ->
                while (c.moveToNext() && list.size < 300) {
                    val id       = c.getString(0) ?: continue
                    val incoming = c.getInt(2) == 1     // 1 = inbox, 2 = sent
                    if (c.getInt(2) != 1 && c.getInt(2) != 2) continue
                    val addrs    = mmsAddresses(id)
                    val from     = addrs.firstOrNull { it.second == 137 }?.first
                    val to       = addrs.filter { it.second == 151 }.map { it.first }
                    val group    = to.size > 1
                    val number   = if (incoming) from else to.firstOrNull()
                    val person   = when {
                        group && !incoming -> "Group (${to.take(3).joinToString { nameFor(it) }}${if (to.size > 3) "…" else ""})"
                        number != null     -> nameFor(number)
                        else               -> "Unknown"
                    }
                    list += Event(
                        time = c.getLong(1) * 1000, person = person, number = number,
                        channel = "MMS", dir = if (incoming) Dir.IN else Dir.OUT,
                        text = mmsText(id).ifBlank { "[picture / attachment]" },
                        unread = incoming && c.getInt(3) == 0, group = group
                    )
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun mmsAddresses(id: String): List<Pair<String, Int>> = try {
        context.contentResolver.query(
            Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), null, null, null
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val a = c.getString(0) ?: continue
                    if (a == "insert-address-token") continue
                    add(a to c.getInt(1))
                }
            }
        } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun mmsText(id: String): String = try {
        context.contentResolver.query(
            Uri.parse("content://mms/part"), arrayOf("ct", "text"), "mid = ?", arrayOf(id), null
        )?.use { c ->
            buildString {
                while (c.moveToNext()) if (c.getString(0) == "text/plain") append(c.getString(1) ?: "")
            }
        } ?: ""
    } catch (_: Exception) { "" }

    private fun callEvents(sinceMs: Long): List<Event> {
        val list = ArrayList<Event>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
                        CallLog.Calls.DATE, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE} > ?", arrayOf(sinceMs.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val num  = c.getString(0) ?: ""
                    val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: if (num.isNotBlank()) nameFor(num) else "Unknown"
                    val dur  = c.getLong(4)
                    val dir  = when (c.getInt(2)) {
                        CallLog.Calls.OUTGOING_TYPE -> Dir.OUT
                        CallLog.Calls.INCOMING_TYPE -> Dir.IN
                        else                        -> Dir.MISSED   // missed, rejected, blocked, voicemail
                    }
                    val text = when {
                        dir == Dir.MISSED -> "missed call"
                        dur >= 60         -> "${dir.name.lowercase()} call, ${dur / 60}m ${dur % 60}s"
                        dur > 0           -> "${dir.name.lowercase()} call, ${dur}s"
                        else              -> "${dir.name.lowercase()} call (not answered)"
                    }
                    list += Event(c.getLong(3), name, num.ifBlank { null }, "Call", dir, text)
                }
            }
        } catch (_: Exception) {}
        return list
    }

    /**
     * Chat notifications from the default SMS app duplicate SMS/MMS already read
     * from the provider. Drop a logged message if the same person sent the same
     * text within two minutes of a provider message.
     */
    private fun dedupe(logged: List<Event>, provider: List<Event>): List<Event> {
        if (logged.isEmpty()) return logged
        val texts = provider.filter { it.channel == "SMS" || it.channel == "MMS" }
        return logged.filter { m ->
            texts.none { p ->
                kotlin.math.abs(p.time - m.time) < 120_000 &&
                    p.text.trim().take(40) == m.text.trim().take(40)
            }
        }
    }

    // ── Views ──────────────────────────────────────────────────────────────

    data class Thread(val person: String, val events: List<Event>) {
        val last get() = events.first()
        val unread get() = events.count { it.unread }
        val missed get() = events.count { it.dir == Dir.MISSED }
        val channels get() = events.map { it.channel }.distinct()
        /** Last thing in the thread came from them (message or missed call). */
        val awaitingYou get() = last.dir != Dir.OUT
    }

    fun threads(sinceMs: Long): List<Thread> =
        events(sinceMs)
            .groupBy { it.person.lowercase() }
            .map { (_, evs) -> Thread(evs.first().person, evs) }
            .sortedByDescending { it.last.time }

    /** Who you've been in touch with, newest first. */
    fun overview(days: Int, limit: Int, unreadOnly: Boolean = false): String =
        overview(days * DAY, "$days day(s)", limit, unreadOnly)

    fun overview(windowMs: Long, label: String, limit: Int, unreadOnly: Boolean = false): String {
        val since = System.currentTimeMillis() - windowMs
        var threads = threads(since)
        if (unreadOnly) threads = threads.filter { it.unread > 0 || it.awaitingYou }
        if (threads.isEmpty()) return "No ${if (unreadOnly) "unanswered " else ""}messages or calls in the last $label.\n" + coverage()
        return buildString {
            appendLine("Conversations — last $label, newest first (now: ${stamp(System.currentTimeMillis())})")
            threads.take(limit).forEach { t ->
                val l = t.last
                val who = when (l.dir) { Dir.OUT -> "You"; Dir.MISSED -> "Missed call"; Dir.IN -> t.person.split(" ").first() }
                val flags = buildList {
                    if (t.unread > 0) add("${t.unread} unread")
                    if (t.missed > 0) add("${t.missed} missed call${if (t.missed > 1) "s" else ""}")
                    if (t.awaitingYou) add("waiting on you")
                }.joinToString(", ")
                appendLine("• ${t.person} — ${ago(l.time)} (${stamp(l.time)}) via ${t.channels.joinToString("/")}" +
                    if (flags.isNotEmpty()) " [$flags]" else "")
                appendLine("    $who: ${l.text.replace('\n', ' ').take(140)}")
            }
            if (threads.size > limit) appendLine("(+${threads.size - limit} more)")
            append(coverage())
        }
    }

    /** Full back-and-forth with one person across SMS, MMS, calls and chat apps. */
    fun timeline(contact: String, days: Int, limit: Int): String {
        val since = System.currentTimeMillis() - days * DAY
        val q = contact.trim().lowercase()
        val numbers = numbersFor(contact).mapNotNull { suffix(it) }.toSet()
        val evs = events(since).filter { e ->
            e.person.lowercase().contains(q) || (suffix(e.number)?.let { it in numbers } == true)
        }
        if (evs.isEmpty()) return "Nothing with \"$contact\" in the last $days day(s) (SMS, MMS, calls, chat apps).\n" + coverage()
        val name = evs.first().person
        return buildString {
            appendLine("$name — last ${evs.size.coerceAtMost(limit)} of ${evs.size} events in $days day(s), oldest → newest")
            evs.take(limit).reversed().forEach { e ->
                val who = when (e.dir) { Dir.OUT -> "You"; Dir.IN -> name.split(" ").first(); Dir.MISSED -> "✗" }
                appendLine("[${stamp(e.time)}] (${e.channel}) $who: ${e.text.replace('\n', ' ').take(200)}" +
                    if (e.unread) " [unread]" else "")
            }
            val last = evs.first()
            appendLine()
            appendLine("Last activity: ${ago(last.time)} — ${if (last.dir == Dir.OUT) "you were last to reach out" else "they were last; waiting on you"}")
            append(coverage())
        }
    }

    private fun numbersFor(name: String): List<String> = try {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null
        )?.use { c -> buildList { while (c.moveToNext()) c.getString(0)?.let { add(it) } } } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    /** Most recent contact time keyed by number suffix and by "name:<lowercase name>". */
    fun lastContactMap(days: Int = 730): Map<String, Long> {
        val map = HashMap<String, Long>()
        events(System.currentTimeMillis() - days * DAY, max = Int.MAX_VALUE).forEach { e ->
            suffix(e.number)?.let { k -> if (e.time > (map[k] ?: 0L)) map[k] = e.time }
            val nk = "name:${e.person.lowercase()}"
            if (e.time > (map[nk] ?: 0L)) map[nk] = e.time
        }
        return map
    }

    fun coverage(): String {
        val first = messageLog(context).firstCaptured()
        val chats = if (first == null)
            "chat apps (RCS, WhatsApp, Signal…): none captured yet — needs VoiceOS notification access"
        else "chat apps (RCS, WhatsApp, Signal…): incoming messages captured from notifications since ${stamp(first)}"
        return "Sources: SMS, MMS, call log; $chats."
    }
}

/**
 * Persists chat-app messages as they arrive via notifications, so RCS and
 * third-party chats exist somewhere VoiceOS can read later. Pruned to 60 days.
 */
class MessageLog(context: Context) : SQLiteOpenHelper(context, "voiceos_messages.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE msgs (
                id     INTEGER PRIMARY KEY AUTOINCREMENT,
                hash   TEXT UNIQUE,
                pkg    TEXT, app TEXT,
                person TEXT, sender TEXT,
                text   TEXT, time INTEGER,
                outgoing INTEGER DEFAULT 0,
                grp    INTEGER DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX msgs_time ON msgs(time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Record the messages in a notification if it is a chat message. Returns rows added. */
    fun capture(sbn: StatusBarNotification, appName: String): Int {
        val n = sbn.notification
        val style = try { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) } catch (_: Exception) { null }
        val isChat = style != null || n.category == Notification.CATEGORY_MESSAGE
        if (!isChat) return 0

        val rows = ArrayList<ContentValues>()
        if (style != null && style.messages.isNotEmpty()) {
            val group = style.isGroupConversation
            val conv  = style.conversationTitle?.toString()?.takeIf { it.isNotBlank() }
            for (m in style.messages) {
                val text   = m.text?.toString()?.takeIf { it.isNotBlank() } ?: continue
                val sender = m.person?.name?.toString()
                // A null person is the device user (a reply sent from the notification)
                val out    = m.person == null ||
                             (style.user.key != null && m.person?.key == style.user.key) ||
                             (sender != null && sender == style.user.name?.toString())
                val person = if (group) conv ?: sender ?: appName else sender ?: conv ?: appName
                rows += row(sbn.packageName, appName, person, sender ?: "", text, m.timestamp.takeIf { it > 0 } ?: sbn.postTime, out, group)
            }
        } else {
            val extras = n.extras
            val title  = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text   = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (title.isEmpty() || text.isEmpty()) return 0
            rows += row(sbn.packageName, appName, title, title, text, sbn.postTime, false, false)
        }

        var added = 0
        val db = writableDatabase
        for (cv in rows) {
            if (db.insertWithOnConflict("msgs", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
        }
        if (added > 0) prune(db)
        return added
    }

    private fun row(pkg: String, app: String, person: String, sender: String, text: String,
                    time: Long, out: Boolean, group: Boolean) = ContentValues().apply {
        // Chat apps re-post the whole recent history on every new message; the hash
        // makes re-captures no-ops.
        put("hash", "$pkg|$person|$sender|$time|${text.take(200)}".hashCode().toString() + ":" + time)
        put("pkg", pkg); put("app", app)
        put("person", person); put("sender", sender)
        put("text", text.take(2000)); put("time", time)
        put("outgoing", if (out) 1 else 0); put("grp", if (group) 1 else 0)
    }

    private fun prune(db: SQLiteDatabase) {
        db.delete("msgs", "time < ?", arrayOf((System.currentTimeMillis() - 60 * 86_400_000L).toString()))
    }

    fun since(sinceMs: Long): List<CommsHub.Event> = try {
        readableDatabase.rawQuery(
            "SELECT app, person, sender, text, time, outgoing, grp FROM msgs WHERE time > ? ORDER BY time DESC LIMIT 1000",
            arrayOf(sinceMs.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val group  = c.getInt(6) == 1
                    val sender = c.getString(2).orEmpty()
                    val text   = c.getString(3).orEmpty()
                    add(CommsHub.Event(
                        time = c.getLong(4), person = c.getString(1).orEmpty(), number = null,
                        channel = c.getString(0).orEmpty(),
                        dir = if (c.getInt(5) == 1) CommsHub.Dir.OUT else CommsHub.Dir.IN,
                        text = if (group && sender.isNotBlank()) "$sender: $text" else text,
                        group = group
                    ))
                }
            }
        }
    } catch (_: Exception) { emptyList() }

    fun firstCaptured(): Long? = try {
        readableDatabase.rawQuery("SELECT MIN(time) FROM msgs", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (_: Exception) { null }
}
