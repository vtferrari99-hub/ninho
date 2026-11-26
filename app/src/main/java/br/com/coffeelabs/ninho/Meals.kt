package br.com.coffeelabs.ninho

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.Calendar

object MealScheduler {
    const val CHANNEL = "refeicoes"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Refeições do dragão", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "Lembretes de café da manhã, almoço e jantar"
            nm.createNotificationChannel(ch)
        }
    }

    fun scheduleAll(ctx: Context) {
        for (m in Meal.entries) schedule(ctx, m)
    }

    fun schedule(ctx: Context, m: Meal) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, m.start)
        c.set(Calendar.MINUTE, 5)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        val intent = Intent(ctx, MealReceiver::class.java).putExtra("meal", m.ordinal)
        val pi = PendingIntent.getBroadcast(ctx, 100 + m.ordinal, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.setWindow(AlarmManager.RTC_WAKEUP, c.timeInMillis, 20 * 60_000L, pi)
    }
}

class MealReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val i = intent.getIntExtra("meal", -1)
        if (i !in 0..2) return
        val m = Meal.entries[i]
        val st = DragonState.load(ctx)
        val now = System.currentTimeMillis()
        if (st.exists && st.stage != Stage.EGG && !st.fedToday(m, now)) {
            val allowed = Build.VERSION.SDK_INT < 33 ||
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (allowed) {
                MealScheduler.ensureChannel(ctx)
                val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val n = Notification.Builder(ctx, MealScheduler.CHANNEL)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle("Hora do ${m.label.lowercase()}")
                    .setContentText("${st.name} está esperando no ninho. Até as ${m.end}h!")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                ctx.getSystemService(NotificationManager::class.java)?.notify(10 + i, n)
            }
        }
        MealScheduler.schedule(ctx, m)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) MealScheduler.scheduleAll(ctx)
    }
}
