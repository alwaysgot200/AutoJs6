package org.autojs.autojs.mgmt.config

import org.autojs.autojs.mgmt.pref.MgmtPref

/**
 * 管理平台服务器地址解析 (方案 §10.2-3, §12 生产拓扑)。
 *
 * 环境差异**不在本文件**, 由构建类型 source set 提供同名 [MgmtEnv], 编译期二选一:
 * - release 变体编译 `app/src/release/java/.../config/MgmtEnv.kt` (生产环境),
 *   恒为生产域名, [MgmtEnv.ALLOW_LOCAL_OVERRIDE] = false, SP 中的历史地址一律忽略;
 * - debug 变体编译 `app/src/debug/java/.../config/MgmtEnv.kt` (开发环境),
 *   默认模拟器宿主机回环 10.0.2.2, [MgmtEnv.ALLOW_LOCAL_OVERRIDE] = true 时
 *   [MgmtPref.serverOverride] 非空可作为本机覆盖 (真机 `adb reverse tcp:8080 tcp:8080`
 *   或局域网调试; 覆盖值只存设备私有 SP, 不进入公开仓库)。
 *
 * 该机制复用上游官方构建脚本已注册的 `src/debug|release/java` source set,
 * 未修改任何上游构建文件 (参见两处 [MgmtEnv] 的 KDoc)。
 *
 * 用户在正常使用中无需也无法手工填写地址; 账号化登录后接入码自动下发。
 */
object MgmtServerConfig {

    /**
     * 生效中的平台根地址 (无尾 `/`), WS/HTTP 链路的唯一取值入口。
     */
    @JvmStatic
    fun serverBaseUrl(): String {
        val override = if (MgmtEnv.ALLOW_LOCAL_OVERRIDE) {
            MgmtPref.serverOverride.trim()
        } else {
            ""
        }
        return override.ifEmpty { MgmtEnv.DEFAULT_BASE_URL }.trimEnd('/')
    }

    /** REST API v1 根地址, 如 https://host/api/v1。 */
    @JvmStatic
    fun apiBaseUrlV1(): String = "${serverBaseUrl()}/api/v1"

    /** 租户自助注册页 (仅允许系统浏览器打开, APP 内不加载/不实现注册表单)。 */
    @JvmStatic
    fun registerPageUrl(): String = "${serverBaseUrl()}/admin/register"

    /** 当前是否生产环境构建 (供 UI 展示环境标识, 业务逻辑禁止据此分叉)。 */
    @JvmStatic
    fun isProduction(): Boolean = MgmtEnv.IS_PRODUCTION
}
