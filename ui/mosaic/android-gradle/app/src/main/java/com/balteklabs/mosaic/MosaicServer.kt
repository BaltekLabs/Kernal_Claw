package com.balteklabs.mosaic

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.balteklabs.voiceos.ReplyRegistry
import android.provider.CalendarContract
import com.balteklabs.voiceos.CommsHub
import com.balteklabs.voiceos.VoiceOSServer
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response

/**
 * The VoiceOS server (agent, tools, tasks, CRM, comms, heartbeat, apps) plus the
 * few routes only the Mosaic tile UI needs.
 *
 *   GET  /api/glance       → live-tile data in one round trip: battery, upcoming
 *                            calendar events, and recent messages / calls.
 *   POST /api/send         → reply from Mosaic without opening another app:
 *                            { via: "sms", number, text }        texts, sent directly
 *                            { via: "reply", app, person, text } chat apps, through
 *                            the notification's own Reply action
 *   GET  /api/send/status  → ?id= result of a text sent with /api/send
 */
class MosaicServer(private val ctx: Context, port: Int) : VoiceOSServer(ctx, port) {

    private val gson = Gson()
    private val smsStatus = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val SMS_SENT = "com.balteklabs.mosaic.SMS_SENT"

    init {
        applyFirstRunDefaults()
        // Delivery reports for texts sent from Mosaic (sent / failed).
        ContextCompat.registerReceiver(ctx, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val id = i.getStringExtra("id") ?: return
                smsStatus[id] = if (resultCode == Activity.RESULT_OK) "sent" else "failed ($resultCode)"
            }
        }, IntentFilter(SMS_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
    }


    /**
     * VoiceOS starts on Ollama; Mosaic starts on Claude. Applied once per install
     * (Mosaic has its own prefs, separate from VoiceOS), so a later choice made in
     * the model picker always sticks.
     */
    private fun applyFirstRunDefaults() {
        val prefs = ctx.getSharedPreferences("voiceos", Context.MODE_PRIVATE)
        if (prefs.getBoolean("mosaic_defaults_v1", false)) return
        val edit = prefs.edit().putBoolean("mosaic_defaults_v1", true)
        if (prefs.getString("provider", "ollama") == "ollama") {
            edit.putString("provider", "anthropic")
                .putString("model", "claude-opus-5-5")
                .putString("model_fast_anthropic", "claude-haiku-5-5")
        }
        edit.apply()
    }

    override fun serve(session: IHTTPSession): Response = when {
        session.uri == "/api/glance" -> glance()
        session.uri == "/api/send" && session.method == Method.POST -> send(body(session))
        session.uri == "/api/send/status" -> json(mapOf("status" to (smsStatus[session.parms["id"] ?: ""] ?: "pending")))
        // Bundled Selawik fonts: give them a real font type (the base server only knows js/css).
        session.uri.startsWith("/static/fonts/") && session.uri.endsWith(".woff2") -> font(session.uri.removePrefix("/static/"))
        else -> super.serve(session)
    }

    private fun font(path: String): Response = try {
        if (path.contains("..")) throw java.io.IOException("bad path")
        newChunkedResponse(Response.Status.OK, "font/woff2", ctx.assets.open("web/$path")).also {
            it.addHeader("Cache-Control", "max-age=86400")
        }
    } catch (_: java.io.IOException) {
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
    }

    private fun glance(): Response {
        val data = mapOf(
            "battery"  to battery(),
            "calendar" to calendar(days = 7, limit = 20),
            "comms"    to comms(days = 7),
            "canSms"   to (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED)
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(data)).also {
            it.addHeader("Cache-Control", "no-cache")
        }
    }

    private fun battery(): Map<String, Any> {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return mapOf(
            "pct"      to (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1),
            "charging" to (bm?.isCharging == true)
        )
    }

    private fun calendar(days: Int, limit: Int): List<Map<String, Any>> = try {
        val now = System.currentTimeMillis()
        val out = mutableListOf<Map<String, Any>>()
        // Instances (not Events) so recurring events show their real next occurrence.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now)
            android.content.ContentUris.appendId(it, now + days * 86_400_000L)
        }.build()
        ctx.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.ALL_DAY),
            null, null, "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val title = c.getString(0) ?: continue
                out += mapOf(
                    "title"    to title,
                    "start"    to c.getLong(1),
                    "end"      to c.getLong(2),
                    "location" to (c.getString(3) ?: ""),
                    "allDay"   to (c.getInt(4) == 1)
                )
            }
        }
        out
    } catch (_: Exception) { emptyList() }   // permission not granted yet

    /**
     * Messages (SMS/MMS + chat apps captured from notifications — Signal, WhatsApp,
     * RCS, DMs…) and calls, kept apart: messages feed the Messaging tile, calls
     * the Phone tile and the Notifications "calls" pivot.
     */
    private fun comms(days: Int): Map<String, Any> = try {
        val events = CommsHub(ctx).events(System.currentTimeMillis() - days * 86_400_000L, 600)
        val dayAgo = System.currentTimeMillis() - 86_400_000L
        val (calls, messages) = events.partition { it.channel == "Call" }
        mapOf(
            "unread"   to messages.count { it.unread },
            "missed"   to calls.count { it.dir == CommsHub.Dir.MISSED && it.time > dayAgo },
            "messages" to messages.take(150).map(::eventMap),
            "calls"    to calls.take(40).map(::eventMap)
        )
    } catch (_: Exception) {
        mapOf("unread" to 0, "missed" to 0, "messages" to emptyList<Any>(), "calls" to emptyList<Any>())
    }

    // ── Sending ────────────────────────────────────────────────────
    private fun send(b: Map<String, Any>): Response {
        val text = (b["text"] as? String)?.trim().orEmpty()
        if (text.isEmpty()) return json(mapOf("error" to "empty"), Response.Status.BAD_REQUEST)
        return when (b["via"]) {
            "sms" -> {
                val number = (b["number"] as? String)?.trim().orEmpty()
                if (number.isEmpty()) return json(mapOf("error" to "missing number"), Response.Status.BAD_REQUEST)
                if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
                    return json(mapOf("error" to "sms_permission"))
                try {
                    val id = java.util.UUID.randomUUID().toString()
                    sendSms(number, text, id)
                    json(mapOf("ok" to true, "id" to id))
                } catch (e: Exception) { json(mapOf("error" to "sms_failed: ${e.message}")) }
            }
            "reply" -> {
                val err = ReplyRegistry.reply(ctx, b["app"] as? String ?: "", b["person"] as? String ?: "", text)
                if (err == null) json(mapOf("ok" to true)) else json(mapOf("error" to err))
            }
            else -> json(mapOf("error" to "unknown via"), Response.Status.BAD_REQUEST)
        }
    }

    /** Send directly. Android files a copy in the SMS history for non-default apps. */
    private fun sendSms(number: String, text: String, id: String) {
        val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ctx.getSystemService(SmsManager::class.java)
                 else @Suppress("DEPRECATION") SmsManager.getDefault()
        val sent = PendingIntent.getBroadcast(ctx, id.hashCode(),
            Intent(SMS_SENT).setPackage(ctx.packageName).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        smsStatus[id] = "pending"
        val parts = sm.divideMessage(text)
        if (parts.size > 1) sm.sendMultipartTextMessage(number, null, parts, ArrayList(List(parts.size) { sent }), null)
        else sm.sendTextMessage(number, null, text, sent, null)
    }

    private fun body(session: IHTTPSession): Map<String, Any> = try {
        val files = HashMap<String, String>()
        session.parseBody(files)
        @Suppress("UNCHECKED_CAST")
        gson.fromJson(files["postData"] ?: "{}", Map::class.java) as Map<String, Any>
    } catch (_: Exception) { emptyMap() }

    private fun json(data: Any, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json", gson.toJson(data))

    private fun eventMap(e: CommsHub.Event) = mapOf(
        "person"  to e.person,
        "number"  to (e.number ?: ""),
        "channel" to e.channel,
        "dir"     to e.dir.name.lowercase(),
        "text"    to e.text.take(200),
        "time"    to e.time,
        "unread"  to e.unread,
        "group"   to e.group,
        // Whether Mosaic can answer this conversation in place (chat apps).
        "canReply" to (e.channel != "Call" && ReplyRegistry.canReply(e.channel, e.person))
    )
}
