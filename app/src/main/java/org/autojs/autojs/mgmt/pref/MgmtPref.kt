package org.autojs.autojs.mgmt.pref

import androidx.preference.PreferenceManager
import org.autojs.autojs.app.GlobalAppContext

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

    /**
     * 用户主动登录态开关 (2026-09-28 起):
     * - true  = 用户已登录, App/服务启动时允许自动建立长连接;
     * - false = 用户已退出登录, 即使地址/秘钥仍保留 (用于登录弹窗回填), 也不得自动上线。
     *
     * 升级兼容: 老版本没有此键, 历史语义是"配置完整即在线", 故缺键时以"地址+秘钥均非空"
     * 推断为已登录, 避免升级后老用户意外掉线; 一旦用户在新 UI 上登录/退出, 此键即被显式写入。
     */
    private const val KEY_SESSION_ENABLED = "key_\$_management_platform_enabled"

    // 平台地址未填写时回落到上游"连接到计算机"地址 (key_$_server_address),
    // 再缺省为网关地址。
    private const val LEGACY_KEY_SERVER_ADDRESS = "key_\$_server_address"

    private val sharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(GlobalAppContext.get())

    // 注意: 默认值参数会被 Kotlin 立即求值, 历史实现把 NetworkUtils.getGatewayAddress()
    // (WifiService binder, 模拟器上实测可阻塞主线程 5s+ 触发 ANR) 作为默认值,
    // 即使键已存在也会在每次读取时执行。这里默认值只给 null/空串, 绝不做 binder/IO;
    // 需要"空地址时建议网关"的场景 (登录弹窗预填) 必须在后台线程自行异步获取。
    @JvmStatic
    var serverAddress: String
        get() = sharedPreferences.getString(KEY_SERVER_ADDRESS, null)
            ?: sharedPreferences.getString(LEGACY_KEY_SERVER_ADDRESS, null).orEmpty()
        set(value) {
            sharedPreferences.edit().putString(KEY_SERVER_ADDRESS, value).apply()
        }

    @JvmStatic
    var secret: String
        get() = sharedPreferences.getString(KEY_SECRET, null).orEmpty()
        set(value) {
            sharedPreferences.edit().putString(KEY_SECRET, value).apply()
        }

    /**
     * 登录态开关 (见 [KEY_SESSION_ENABLED] 契约说明)。
     * 凭证完整且开关为 true 时才允许自动连接; 退出登录只置 false, 凭证保留回填。
     */
    @JvmStatic
    var sessionEnabled: Boolean
        get() {
            val sp = sharedPreferences
            return if (sp.contains(KEY_SESSION_ENABLED)) {
                sp.getBoolean(KEY_SESSION_ENABLED, false)
            } else {
                serverAddress.isNotEmpty() && secret.isNotEmpty()
            }
        }
        set(value) {
            sharedPreferences.edit().putBoolean(KEY_SESSION_ENABLED, value).apply()
        }
}
