package org.autojs.autojs.mgmt.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.autojs.autojs.mgmt.client.ConnectionIssue
import org.autojs.autojs.mgmt.client.ConnectionState
import org.autojs.autojs.mgmt.client.ConnectionStatus
import org.autojs.autojs.mgmt.client.ManagementPlatformClient
import org.autojs.autojs.mgmt.pref.MgmtPref
import org.autojs.autojs.ui.main.MainActivity
import org.autojs.autojs6.R

class ManagementPlatformService : Service() {

    companion object {
        private const val CHANNEL_ID = "management_platform_service"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_CODE_OPEN_MAIN = 100
    }

    private val statusListener = ManagementPlatformClient.ConnectionStateListener { newStatus ->
        renderNotification(newStatus)
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

            runCatching { startForeground(NOTIFICATION_ID, buildNotification(ManagementPlatformClient.status)) }
                .onFailure { stopSelf(); return }
        } else {
            // @mgmt-adapt API 26 以下使用无 channel 构造器, 定点抑制 Java 废弃警告。
            runCatching {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, buildNotification(ManagementPlatformClient.status))
            }
                .onFailure { stopSelf(); return }
        }

        // 连接状态 (三态/原因/安静窗口升级) 实时同步到常驻通知, 用户无需打开 App
        // 即可知道"正在自动重连/本机断网/秘钥失效"。
        ManagementPlatformClient.addConnectionStateListener(statusListener)

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
        ManagementPlatformClient.removeConnectionStateListener(statusListener)
        // 显式取消常驻通知: logout 时 DISCONNECTED 状态回调可能已在主线程队列中、
        // 早于系统回收 FGS 通知执行, 不主动 cancel 可能残留一条"正在保持连接"的僵尸通知。
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.cancel(NOTIFICATION_ID) }
        super.onDestroy()
    }

    /**
     * 按最新连接状态刷新常驻通知 (复用同一 NOTIFICATION_ID, 不产生新通知条目)。
     */
    private fun renderNotification(newStatus: ConnectionStatus) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(newStatus)) }
    }

    private fun buildNotification(newStatus: ConnectionStatus): Notification {
        val contentText = notificationText(newStatus)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.mgmt_notif_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.autojs6_material)
            .setOnlyAlertOnce(true) // 状态刷新不发出声音/不震动 (渠道本身也是 LOW)
            .setContentIntent(openMainPendingIntent()) // 点击直达主界面, 便于秘钥失效时快速重新登录
            .build()
    }

    /**
     * 状态到文案的映射: 安静窗口内一律展示默认文案 (用户无感);
     * 升级后按"重连中 / 本机断网 / 服务器不可达 / 秘钥失效"区分。
     */
    private fun notificationText(newStatus: ConnectionStatus): String = when {
        !newStatus.escalated -> getString(R.string.mgmt_notif_default)
        newStatus.state == ConnectionState.RECOVERING -> getString(R.string.mgmt_notif_recovering)
        else -> when (newStatus.issue) {
            ConnectionIssue.LOCAL_NETWORK_LOST -> getString(R.string.mgmt_notif_local_lost)
            ConnectionIssue.AUTH_REJECTED -> getString(R.string.mgmt_notif_auth_rejected)
            ConnectionIssue.SERVER_UNREACHABLE -> getString(R.string.mgmt_notif_server_unreachable)
            ConnectionIssue.NONE -> getString(R.string.mgmt_notif_recovering)
        }
    }

    private fun openMainPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT
            .or(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, REQUEST_CODE_OPEN_MAIN, intent, flags)
    }

    private fun hasValidConfig(): Boolean {
        val address = MgmtPref.serverAddress.trim()
        val secret = MgmtPref.secret.trim()
        // 用户已退出登录时即使凭证保留也不得自动上线 (服务随即 stopSelf, 不滞留通知栏)。
        return address.isNotEmpty() && secret.isNotEmpty() && MgmtPref.sessionEnabled
    }
}
