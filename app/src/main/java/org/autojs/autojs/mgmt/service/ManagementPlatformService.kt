package org.autojs.autojs.mgmt.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.autojs.autojs.mgmt.pref.MgmtPref
import org.autojs.autojs.mgmt.client.ManagementPlatformClient
import org.autojs.autojs6.R

class ManagementPlatformService : Service() {

    companion object {
        private const val CHANNEL_ID = "management_platform_service"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Management Platform Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)

            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("AutoJs6 管理服务")
                .setContentText("正在保持与管理平台的连接")
                .setSmallIcon(R.drawable.autojs6_material)
                .build()
            runCatching { startForeground(NOTIFICATION_ID, notification) }
                .onFailure { stopSelf(); return }
        } else {
            // @mgmt-adapt API 26 以下使用无 channel 构造器, 定点抑制 Java 废弃警告。
            @Suppress("DEPRECATION")
            val notification = Notification.Builder(this)
                .setContentTitle("AutoJs6 管理服务")
                .setContentText("正在保持与管理平台的连接")
                .setSmallIcon(R.drawable.autojs6_material)
                .build()
            runCatching { startForeground(NOTIFICATION_ID, notification) }
                .onFailure { stopSelf(); return }
        }

        if (!hasValidConfig()) {
            stopSelf()
            return
        }
        // 连接只在 onStartCommand 发起: 单次 startService 也会先回调 onCreate 再回调
        // onStartCommand, 两处都连会创建两条 WebSocket, 服务端按 deviceId 互相顶替,
        // 表现为反复 onFailure/重连抖动。
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasValidConfig()) {
            stopSelf()
            return START_NOT_STICKY
        }

        ManagementPlatformClient.connectIfConfigured()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun hasValidConfig(): Boolean {
        val address = MgmtPref.serverAddress.trim()
        val secret = MgmtPref.secret.trim()
        // 用户已退出登录时即使凭证保留也不得自动上线 (服务随即 stopSelf, 不滞留通知栏)。
        return address.isNotEmpty() && secret.isNotEmpty() && MgmtPref.sessionEnabled
    }
}
