package com.example.dndscheduler

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.dndscheduler.Store.owns
import com.example.dndscheduler.Store.previousFilter
import java.time.ZonedDateTime

class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.sync(context, applyNow = true)
    }
}

object Scheduler {

    fun nextTransition(list: List<Schedule>, now: ZonedDateTime): ZonedDateTime? =
        list.filter { it.enabled }.flatMap { it.transitions(now) }.minByOrNull { it.toInstant() }

    private fun pending(c: Context) = PendingIntent.getBroadcast(
        c, 0,
        Intent(c, SyncReceiver::class.java).setAction("com.example.dndscheduler.ALARM"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Turns DND on/off according to the schedules (if applyNow) and
     * sets an alarm for the next start/end moment.
     */
    fun sync(c: Context, applyNow: Boolean = true) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val am = c.getSystemService(AlarmManager::class.java)
        val list = Store.load(c)
        val now = ZonedDateTime.now()

        if (applyNow && nm.isNotificationPolicyAccessGranted) {
            val active = list.any { it.enabled && it.isActive(now) }
            if (active) {
                if (!c.owns) {
                    c.previousFilter = nm.currentInterruptionFilter
                    c.owns = true
                }
                nm.setInterruptionFilter(
                    when (Store.mode(c)) {
                        1 -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                        2 -> NotificationManager.INTERRUPTION_FILTER_NONE
                        else -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    }
                )
            } else if (c.owns) {
                val prev = c.previousFilter
                nm.setInterruptionFilter(
                    if (prev == NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
                        NotificationManager.INTERRUPTION_FILTER_ALL else prev
                )
                c.owns = false
            }
        }

        val pi = pending(c)
        am.cancel(pi)
        val next = nextTransition(list, now) ?: return
        val at = next.toInstant().toEpochMilli()
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}
