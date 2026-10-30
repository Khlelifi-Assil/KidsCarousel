package com.kids.carousel

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

object Config { const val HOUR = 16; const val MIN = 0 } // heure du lancement quotidien automatique

object AlarmHelper {
    fun schedule(c: Context, hour: Int, min: Int) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val trigger = PendingIntent.getBroadcast(c, 1, Intent(c, LaunchReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val show = PendingIntent.getActivity(c, 2, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        c.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, show), trigger)
    }
}

class LaunchReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("kids", "Kids", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(c, 0,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(1, Notification.Builder(c, "kids")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("C'est l'heure d'apprendre !")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi).setAutoCancel(true).build())
        AlarmHelper.schedule(c, Config.HOUR, Config.MIN)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = AlarmHelper.schedule(c, Config.HOUR, Config.MIN)
}
