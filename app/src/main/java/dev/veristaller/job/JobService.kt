package dev.veristaller.job

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.veristaller.R

class JobService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_veristaller)
            .setContentTitle("Veristaller")
            .setContentText(if (pkg.isBlank()) "Working…" else "Replacing base.apk for $pkg")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "job_progress"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_PACKAGE = "package"

        fun start(context: Context, packageName: String) {
            val intent = Intent(context, JobService::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                // Background-start restrictions: the job still runs in-process.
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, JobService::class.java)) }
        }
    }
}
