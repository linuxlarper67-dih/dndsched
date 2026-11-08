package com.example.dndscheduler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID

const val MAX_SCHEDULES = 30

/** days: 1 = Monday ... 7 = Sunday. Minutes are minutes since midnight. */
data class Schedule(
    val id: String = UUID.randomUUID().toString(),
    val days: Set<Int>,
    val startMin: Int,
    val endMin: Int,
    val enabled: Boolean = true,
) {
    private val overnight get() = endMin < startMin

    fun isActive(now: ZonedDateTime): Boolean {
        val t = now.hour * 60 + now.minute
        val today = now.dayOfWeek.value
        val yesterday = if (today == 1) 7 else today - 1
        return if (!overnight) {
            today in days && t >= startMin && t < endMin
        } else {
            (today in days && t >= startMin) || (yesterday in days && t < endMin)
        }
    }

    /** All start/end moments after [now] within the next ~week. */
    fun transitions(now: ZonedDateTime): List<ZonedDateTime> {
        val out = mutableListOf<ZonedDateTime>()
        for (offset in -1L..8L) {
            val date = now.toLocalDate().plusDays(offset)
            if (date.dayOfWeek.value !in days) continue
            val start = ZonedDateTime.of(date, LocalTime.of(startMin / 60, startMin % 60), now.zone)
            val endDate = if (overnight) date.plusDays(1) else date
            val end = ZonedDateTime.of(endDate, LocalTime.of(endMin / 60, endMin % 60), now.zone)
            if (start.isAfter(now)) out += start
            if (end.isAfter(now)) out += end
        }
        return out
    }
}

object Store {
    private fun p(c: Context) = c.getSharedPreferences("dnd", Context.MODE_PRIVATE)

    fun load(c: Context): List<Schedule> {
        val arr = JSONArray(p(c).getString("schedules", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val d = o.getJSONArray("days")
            Schedule(
                id = o.getString("id"),
                days = (0 until d.length()).map { j -> d.getInt(j) }.toSet(),
                startMin = o.getInt("start"),
                endMin = o.getInt("end"),
                enabled = o.getBoolean("on"),
            )
        }
    }

    fun save(c: Context, list: List<Schedule>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("days", JSONArray(s.days.toList()))
                put("start", s.startMin)
                put("end", s.endMin)
                put("on", s.enabled)
            })
        }
        p(c).edit().putString("schedules", arr.toString()).apply()
    }

    /** 0 = priority only, 1 = alarms only, 2 = total silence */
    fun mode(c: Context) = p(c).getInt("mode", 0)
    fun setMode(c: Context, m: Int) = p(c).edit().putInt("mode", m).apply()

    var Context.owns: Boolean
        get() = p(this).getBoolean("owns", false)
        set(v) = p(this).edit().putBoolean("owns", v).apply()

    var Context.previousFilter: Int
        get() = p(this).getInt("prev", 1)
        set(v) = p(this).edit().putInt("prev", v).apply()
}
