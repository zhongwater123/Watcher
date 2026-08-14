package com.example.watcher

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service that keeps the app alive during long-running tasks
 * (monitoring, video analysis, gateway serving).
 *
 * Usage:
 *   WatcherForegroundService.start(context, "实时监控运行中")
 *   WatcherForegroundService.updateMessage(context, "视频分析进行中 (3/10)")
 *   WatcherForegroundService.stop(context)
 */
class WatcherForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Notification "停止" button: force-stop everything
                activeReasons.clear()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Reason already tracked by companion start/stop — just show notification
        val reason = intent?.getStringExtra(EXTRA_REASON)
        if (reason != null) {
            activeReasons.add(reason)
        }

        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: buildMessageFromReasons()
        val notification = buildNotification(message)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        activeReasons.clear()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun buildMessageFromReasons(): String {
        if (activeReasons.isEmpty()) return "Watcher 正在后台运行"
        val labels = activeReasons.map { reason ->
            when (reason) {
                REASON_MONITOR -> "实时监控"
                REASON_VIDEO -> "视频分析"
                REASON_NTFY_RELAY -> "ntfy 消息通道"
                else -> reason
            }
        }
        return "${labels.joinToString(" · ")} 运行中"
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台任务保活",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "监控、视频分析等长时间任务运行时的常驻通知"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WatcherForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Watcher")
            .setContentText(message)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "停止", stopPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Watcher::ForegroundTask"
        ).apply {
            acquire(4 * 60 * 60 * 1000L) // 4 hours max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "watcher_foreground_task"
        private const val NOTIFICATION_ID = 9001
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_REASON = "reason"
        private const val ACTION_STOP = "com.example.watcher.STOP_FOREGROUND"

        const val REASON_MONITOR = "monitor"
        const val REASON_VIDEO = "video"
        const val REASON_NTFY_RELAY = "ntfy_relay"

        private val activeReasons = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        fun start(context: Context, message: String = "Watcher 正在后台运行", reason: String? = null) {
            val intent = Intent(context, WatcherForegroundService::class.java).apply {
                putExtra(EXTRA_MESSAGE, message)
                reason?.let { putExtra(EXTRA_REASON, it) }
            }
            context.startForegroundService(intent)
        }

        fun updateMessage(context: Context, message: String) {
            start(context, message)
        }

        fun stop(context: Context, reason: String? = null) {
            if (reason != null) {
                activeReasons.remove(reason)
                if (activeReasons.isEmpty()) {
                    // No more reasons — stop the service directly
                    context.stopService(Intent(context, WatcherForegroundService::class.java))
                } else {
                    // Still active for other reasons — just update notification
                    start(context, reason = null)
                }
            } else {
                // Force stop all
                activeReasons.clear()
                context.stopService(Intent(context, WatcherForegroundService::class.java))
            }
        }
    }
}
