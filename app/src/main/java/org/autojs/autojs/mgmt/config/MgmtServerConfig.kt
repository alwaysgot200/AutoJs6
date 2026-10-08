package org.autojs.autojs.mgmt.config

import org.autojs.autojs.mgmt.pref.MgmtPref
import org.autojs.autojs6.BuildConfig

/**
 * 管理平台服务器地址解析 (方案 §10.2-3, §12 生产拓扑)。
 *
 * - release 构建: 恒为生产域名常量 [PROD_BASE_URL] (与站点/后台/API/WS 同源拓扑,
 *   不硬编码内部端口), SP 中的历史地址一律忽略;
 * - debug 构建: 默认走模拟器宿主机回环 [DEBUG_DEFAULT_BASE_URL] (dev nginx 统一入口
 *   8080, /api/v1 与 /ws/device 同口), [MgmtPref.serverOverride] 非空时作为手工覆盖
 *   (真机经 `adb reverse tcp:8080 tcp:8080` 时可覆盖为 http://localhost:8080)。
 *
 * 用户在正常使用中无需也无法手工填写地址; 账号化登录后接入码自动下发。
 */
object MgmtServerConfig {

    /** 生产环境统一入口 (nginx: /、/admin/、/api/v1、/ws/device 同源)。 */
    const val PROD_BASE_URL: String = "https://autojs6.80fafa.com"

    /** 模拟器访问宿主机 dev 统一入口的回环地址 (10.0.2.2 -> 宿主机 localhost)。 */
    const val DEBUG_DEFAULT_BASE_URL: String = "http://10.0.2.2:8080"

    /**
     * 生效中的平台根地址 (无尾 /), WS/HTTP 链路的唯一取值入口。
     */
    @JvmStatic
    fun serverBaseUrl(): String {
        val base = if (BuildConfig.DEBUG) {
            MgmtPref.serverOverride.trim().ifEmpty { DEBUG_DEFAULT_BASE_URL }
        } else {
            PROD_BASE_URL
        }
        return base.trimEnd('/')
    }

    /** REST API v1 根地址, 如 https://host/api/v1。 */
    @JvmStatic
    fun apiBaseUrlV1(): String = "${serverBaseUrl()}/api/v1"

    /** 租户自助注册页 (仅允许系统浏览器打开, APP 内不加载/不实现注册表单)。 */
    @JvmStatic
    fun registerPageUrl(): String = "${serverBaseUrl()}/admin/register"
}
