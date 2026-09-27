package org.autojs.autojs.mgmt.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.AttributeSet
import androidx.preference.PreferenceManager
import org.autojs.autojs.mgmt.pref.MgmtPref
import org.autojs.autojs.mgmt.client.ManagementPlatformClient
import org.autojs.autojs.mgmt.service.ManagementPlatformService
import org.autojs.autojs.theme.preference.MaterialPreference
import org.autojs.autojs.util.ViewUtils
import org.autojs.autojs6.R

/**
 * 管理平台连接入口 Preference
 *
 * 状态语义（标题实时反映正式长连接状态）：
 * - 未连接："测试连接管理平台"，点击发起鉴权测试；成功后立即拉起前台服务建立正式持久连接；
 * - 已连接："已连接到平台"，点击仅提示当前状态，不重复发起测试。
 */
class ManagementPlatformTestConnectionPreference : MaterialPreference,
    ManagementPlatformClient.ConnectionStateListener {

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context) : super(context)

    init {
        renderConnected(ManagementPlatformClient.isConnected)
    }

    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)
        ManagementPlatformClient.addConnectionStateListener(this)
    }

    override fun onPrepareForRemoval() {
        super.onPrepareForRemoval()
        ManagementPlatformClient.removeConnectionStateListener(this)
    }

    override fun onConnectionStateChanged(connected: Boolean) {
        // Client 已保证回调在主线程
        renderConnected(connected)
    }

    private fun renderConnected(connected: Boolean) {
        setTitle(if (connected) R.string.text_management_platform_connected else R.string.text_management_platform_test_connection)
    }

    override fun onClick() {
        if (ManagementPlatformClient.isConnected) {
            ViewUtils.showToast(prefContext, R.string.text_management_platform_connected)
            super.onClick()
            return
        }
        val address = MgmtPref.serverAddress.trim()
        if (address.isEmpty()) {
            ViewUtils.showToast(prefContext, R.string.text_management_platform_server_address, true)
            super.onClick()
            return
        }
        ManagementPlatformClient.testConnection { success, message ->
            if (success) {
                // 测试通过 == 配置可用: 立即拉起前台服务建立正式持久连接,
                // 设备随即在平台上线 (服务在 App 冷启动时也会自启, START_STICKY 保活)。
                val intent = Intent(prefContext, ManagementPlatformService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    prefContext.startForegroundService(intent)
                } else {
                    prefContext.startService(intent)
                }
                ViewUtils.showToast(prefContext, R.string.text_management_platform_connection_success)
            } else {
                val detail = message ?: "unknown"
                val text = prefContext.getString(R.string.text_management_platform_connection_failed, detail)
                ViewUtils.showToast(prefContext, text, true)
            }
        }
        super.onClick()
    }
}
