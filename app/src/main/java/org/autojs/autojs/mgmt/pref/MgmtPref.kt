package org.autojs.autojs.mgmt.pref

import androidx.preference.PreferenceManager
import org.autojs.autojs.app.GlobalAppContext
import org.autojs.autojs.mgmt.config.MgmtServerConfig

/**
 * 管理平台薄层 (org.autojs.autojs.mgmt) 的配置存取。
 *
 * 独立于上游 org.autojs.autojs.core.pref.Pref, 直接读写默认 SharedPreferences,
 * 不改动上游 Pref.kt。存储键为固定契约, 不得改名:
 * 已在设备上填写过的服务器地址/秘钥依赖此二键, 改名会导致升级后配置丢失。
 *
 * 2026-10-08 账号化改造 (方案 §10):
 * - [serverAddress] 不再是用户手填项, 统一返回 [MgmtServerConfig] 解析的环境地址
 *   (release=生产域名常量, debug=SP 覆盖 ?: 模拟器默认地址);
 * - [serverOverride] 仅在 debug 构建下作为手工覆盖 (真机 adb reverse / 局域网调试);
 * - [secret] 存储租户接入码 (设备端 matchCode), 登录后由 AccessCodeClient 自动写入,
 *   用户无需手工填写; 升级安装沿用历史值, 字面键名逐字符不变。
 */
object MgmtPref {

    private const val KEY_SERVER_ADDRESS = "key_\$_management_platform_server_address"
    private const val KEY_SECRET = "key_\$_management_platform_secret"

    /**
     * 用户主动登录态开关 (2026-09-28 起):
     * - true  = 用户已登录, App/服务启动时允许自动建立长连接;
     * - false = 用户已退出登录, 即使秘钥仍保留也不得自动上线。
     *
     * 2026-10-08 起其语义等同于"账号会话存在" (AccountSession 登录成功置 true、
     * 退出登录置 false); 缺键一律按未登录处理 —— 老版本升级后需用租户账号重新登录
     * 一次 (登录后自动拉取与历史值相同的 matchCode, WS 行为不变)。
     */
    private const val KEY_SESSION_ENABLED = "key_\$_management_platform_enabled"

    // 平台地址未填写时回落到上游"连接到计算机"地址 (key_$_server_address)。
    // 仅作为 debug 构建下的手工覆盖读取 (见 MgmtServerConfig)。
    private const val LEGACY_KEY_SERVER_ADDRESS = "key_\$_server_address"

    private val sharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(GlobalAppContext.get())

    /**
     * 生效中的服务器地址 (供 WS/HTTP 全部链路使用): 环境地址常量 + debug 覆盖解析,
     * 见 [MgmtServerConfig]。禁止在调用方自行读取 SP 原始键拼地址。
     */
    @JvmStatic
    var serverAddress: String
        get() = MgmtServerConfig.serverBaseUrl()
        set(value) {
            // 保留 setter 以兼容历史调用; 写入的是 debug 覆盖键 (release 读取时被忽略)。
            sharedPreferences.edit().putString(KEY_SERVER_ADDRESS, value).apply()
        }

    /**
     * debug 手工覆盖地址 (含历史"连接到计算机"回落键); release 构建返回空串。
     * 注意: 默认值参数会被 Kotlin 立即求值, 这里绝不做 NetworkUtils/WifiService binder 调用。
     */
    @JvmStatic
    val serverOverride: String
        get() = sharedPreferences.getString(KEY_SERVER_ADDRESS, null)
            ?: sharedPreferences.getString(LEGACY_KEY_SERVER_ADDRESS, null).orEmpty()

    @JvmStatic
    var secret: String
        get() = sharedPreferences.getString(KEY_SECRET, null).orEmpty()
        set(value) {
            sharedPreferences.edit().putString(KEY_SECRET, value).apply()
        }

    /**
     * 登录态开关。账号会话存在 (JWT 已持久化) 时才允许自动连接。
     */
    @JvmStatic
    var sessionEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_SESSION_ENABLED, false)
        set(value) {
            sharedPreferences.edit().putBoolean(KEY_SESSION_ENABLED, value).apply()
        }
}
