package org.autojs.autojs.mgmt.pref

import androidx.preference.PreferenceManager
import org.autojs.autojs.app.GlobalAppContext
import org.autojs.autojs.util.NetworkUtils

/**
 * 管理平台薄层 (org.autojs.autojs.mgmt) 的配置存取。
 *
 * 独立于上游 org.autojs.autojs.core.pref.Pref, 直接读写默认 SharedPreferences,
 * 不改动上游 Pref.kt。存储键为固定契约, 不得改名:
 * 已在设备上填写过的服务器地址/秘钥依赖此二键, 改名会导致升级后配置丢失。
 */
object MgmtPref {

    private const val KEY_SERVER_ADDRESS = "key_\$_management_platform_server_address"
    private const val KEY_SECRET = "key_\$_management_platform_secret"

    // 平台地址未填写时回落到上游"连接到计算机"地址 (key_$_server_address),
    // 再缺省为网关地址。
    private const val LEGACY_KEY_SERVER_ADDRESS = "key_\$_server_address"

    private val sharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(GlobalAppContext.get())

    @JvmStatic
    var serverAddress: String
        get() = sharedPreferences.getString(KEY_SERVER_ADDRESS, legacyPcServerAddress).orEmpty()
        set(value) {
            sharedPreferences.edit().putString(KEY_SERVER_ADDRESS, value).apply()
        }

    @JvmStatic
    var secret: String
        get() = sharedPreferences.getString(KEY_SECRET, null).orEmpty()
        set(value) {
            sharedPreferences.edit().putString(KEY_SECRET, value).apply()
        }

    private val legacyPcServerAddress: String
        get() = sharedPreferences.getString(LEGACY_KEY_SERVER_ADDRESS, NetworkUtils.getGatewayAddress()).orEmpty()
}
