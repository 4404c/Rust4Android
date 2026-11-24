package dev.rustdroid.ide.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.rustdroid.ide.MainActivity
import dev.rustdroid.ide.R

/**
 * R4A: mantiene viva la app mientras la IA trabaja (aunque salgas del
 * proyecto o de la app), con una notificación fija "La IA está trabajando…".
 * Al terminar, si no estabas mirando ese proyecto, avisa con otra notificación.
 */
class AiWorkService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels(this)
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: getString(R.string.ai_notif_working_generic)
        val notif = base(this, CHANNEL_WORKING)
            .setContentTitle(getString(R.string.ai_notif_working_title))
            .setContentText(text)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_WORKING, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_WORKING, notif)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_WORKING = "ai-working"
        private const val CHANNEL_DONE = "ai-done"
        private const val NOTIF_WORKING = 77
        private const val EXTRA_TEXT = "text"

        /** Arranca (o actualiza) el servicio con el nombre de los proyectos en los que trabaja. */
        fun update(context: Context, projects: List<String>) {
            val app = context.applicationContext
            if (projects.isEmpty()) {
                app.stopService(Intent(app, AiWorkService::class.java))
                return
            }
            val intent = Intent(app, AiWorkService::class.java)
                .putExtra(EXTRA_TEXT, app.getString(R.string.ai_notif_working_text, projects.joinToString(", ")))
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
            }
        }

        /** "La IA terminó en <proyecto>". Tocarla abre R4A. */
        fun notifyFinished(context: Context, project: String, error: Boolean) {
            val app = context.applicationContext
            createChannels(app)
            val n = base(app, CHANNEL_DONE)
                .setContentTitle(app.getString(if (error) R.string.ai_notif_error_title else R.string.ai_notif_done_title, project))
                .setContentText(app.getString(R.string.ai_notif_done_text))
                .setAutoCancel(true)
                .build()
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            runCatching { nm.notify(1000 + (project.hashCode() and 0xffff), n) }
        }

        private fun base(context: Context, channel: String): Notification.Builder {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel) else Notification.Builder(context)
            return b.setSmallIcon(R.drawable.ic_stat_rustdroid).setContentIntent(open)
        }

        private fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_WORKING, context.getString(R.string.ai_notif_channel_working), NotificationManager.IMPORTANCE_LOW),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, context.getString(R.string.ai_notif_channel_done), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}
