package com.balteklabs.mosaic

import android.content.Context
import android.os.BatteryManager
import android.provider.CalendarContract
import com.balteklabs.voiceos.CommsHub
import com.balteklabs.voiceos.VoiceOSServer
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response

/**
 * The VoiceOS server (agent, tools, tasks, CRM, comms, heartbeat, apps) plus the
 * few routes only the Mosaic tile UI needs.
 *
 *   GET /api/glance  → live-tile data in one round trip: battery, upcoming
 *                      calendar events, and recent messages / calls.
 */
class MosaicServer(private val ctx: Context, port: Int) : VoiceOSServer(ctx, port) {

    private val gson = Gson()

    init { applyFirstRunDefaults() }

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

    override fun serve(session: IHTTPSession): Response =
        if (session.uri == "/api/glance") glance() else super.serve(session)

    private fun glance(): Response {
        val data = mapOf(
            "battery"  to battery(),
            "calendar" to calendar(days = 7, limit = 20),
            "comms"    to comms(days = 3, limit = 25)
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

    private fun comms(days: Int, limit: Int): Map<String, Any> = try {
        val events = CommsHub(ctx).events(System.currentTimeMillis() - days * 86_400_000L, 300)
        val dayAgo = System.currentTimeMillis() - 86_400_000L
        mapOf(
            "unread" to events.count { it.unread },
            "missed" to events.count { it.dir == CommsHub.Dir.MISSED && it.time > dayAgo },
            "recent" to events.take(limit).map { e ->
                mapOf(
                    "person"  to e.person,
                    "number"  to (e.number ?: ""),
                    "channel" to e.channel,
                    "dir"     to e.dir.name.lowercase(),
                    "text"    to e.text.take(140),
                    "time"    to e.time,
                    "unread"  to e.unread
                )
            }
        )
    } catch (_: Exception) {
        mapOf("unread" to 0, "missed" to 0, "recent" to emptyList<Any>())
    }
}
