package com.vlatal.flashcards

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vlatal.flashcards.data.AutoBackup
import com.vlatal.flashcards.data.Locales
import com.vlatal.flashcards.data.Repository
import com.vlatal.flashcards.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/** Daily reminder (an inexact alarm, so no special permission is needed). */
object Reminders {
    private const val CHANNEL = "daily"
    private const val NOTIFICATION_ID = 1

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context)
        am.cancel(pi)
        val s = Settings.get(context).current
        if (!s.reminderEnabled) return
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, s.reminderMinutes / 60)
            set(Calendar.MINUTE, s.reminderMinutes % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 30_000) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** @param force show it even when there is nothing to study (test notification from settings). */
    suspend fun notifyIfNeeded(context: Context, force: Boolean = false) {
        val t = Repository.get(context).today()
        val work = t.reviewsDue + t.newAvailable
        // goal reached and nothing to review — don't disturb
        if (!force && (work == 0 || (t.answers >= t.goal && t.reviewsDue == 0))) return
        if (!canNotify(context)) return
        val res = Locales.wrap(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, res.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val parts = buildList {
            if (t.reviewsDue > 0) add(res.getString(R.string.notif_review, t.reviewsDue))
            if (t.newAvailable > 0) add(res.getString(R.string.notif_new, t.newAvailable))
        }.joinToString(" · ").ifEmpty { res.getString(R.string.notif_all_done) }
        val title = if (t.streak > 0)
            res.getString(R.string.notif_streak, res.resources.getQuantityString(R.plurals.days_count, t.streak, t.streak))
        else res.getString(R.string.notif_title)
        nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(res.getString(R.string.notif_text, parts))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Reminders.notifyIfNeeded(context)
                AutoBackup.runIfDue(context)
            } catch (e: Exception) {
                android.util.Log.e("Karticky", "reminder failed", e)
            } finally {
                Reminders.schedule(context) // the next one is tomorrow
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Reminders.schedule(context)
        }
    }
}
