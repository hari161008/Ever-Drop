package com.sameerasw.medrop.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sameerasw.medrop.MainActivity
import com.sameerasw.medrop.R
import com.sameerasw.medrop.utils.EverDropWifiDirectManager

/**
 * EverDropReceiveService
 *
 * Runs in the background as a Foreground Service when receiving is enabled,
 * keeping the Wi-Fi Direct socket and Autonomous Group Owner active so nearby
 * devices can discover and beam content even when the app is in the background.
 */
class EverDropReceiveService : Service() {

    companion object {
        const val CHANNEL_ID = "everdrop_bg_receive_channel"
        const val NOTIFICATION_ID = 8888

        const val ACTION_START = "com.sameerasw.medrop.action.START_RECEIVER"
        const val ACTION_STOP = "com.sameerasw.medrop.action.STOP_RECEIVER"
        const val ACTION_ACCEPT = "com.sameerasw.medrop.action.ACCEPT_TRANSFER"
        const val ACTION_REJECT = "com.sameerasw.medrop.action.REJECT_TRANSFER"

        fun start(context: Context) {
            val intent = Intent(context, EverDropReceiveService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, EverDropReceiveService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification()
                EverDropWifiDirectManager.startDiscoverableReceiver(this)
            }
            ACTION_STOP -> {
                if (!EverDropWifiDirectManager.isTransferBusy()) {
                    if (!EverDropWifiDirectManager.isAppInForeground) {
                        EverDropWifiDirectManager.stopDiscoverableReceiver()
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                    stopSelf()
                }
            }
            ACTION_ACCEPT -> {
                startForegroundNotification()
                EverDropWifiDirectManager.acceptIncomingTransfer(this)
            }
            ACTION_REJECT -> {
                startForegroundNotification()
                EverDropWifiDirectManager.rejectIncomingTransfer(this)
                if (!EverDropWifiDirectManager.isReceiverActive.value) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                    stopSelf()
                }
            }
            else -> {
                startForegroundNotification()
                EverDropWifiDirectManager.startDiscoverableReceiver(this)
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ever Drop Background Receive",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps device discoverable for peer-to-peer Wi-Fi Direct transfers"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.rounded_share_24)
            .setContentTitle("Ever Drop • Ready to receive")
            .setContentText("Discoverable to nearby devices via Wi-Fi Direct")
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        if (!EverDropWifiDirectManager.isTransferBusy() && !EverDropWifiDirectManager.isAppInForeground) {
            EverDropWifiDirectManager.stopDiscoverableReceiver()
        }
        super.onDestroy()
    }
}
