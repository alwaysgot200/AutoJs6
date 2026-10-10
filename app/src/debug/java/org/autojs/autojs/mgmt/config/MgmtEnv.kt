package org.autojs.autojs.mgmt.config

/**
 * 开发环境接入配置 —— 仅编译进 debug 构建类型 (appDebug / inrtDebug)。
 *
 * 同名类在 release source set (`app/src/release/java/.../MgmtEnv.kt`) 另有生产实现,
 * 编译期按构建类型二选一, 无需也禁止修改上游 `app/build.gradle.kts`
 * (`src/debug/java`、`src/release/java` 由上游官方构建脚本原生注册,
 * 上游 `LeakCanarySetup` 即同款同包同名双实现机制)。
 *
 * Git 安全 (fork 为公开开源仓库):
 * - 本文件只允许放**非敏感默认值** —— 模拟器回环别名 `10.0.2.2` 是 Android 模拟器
 *   固定指向宿主机的通用别名, 不含任何机器/网络特征;
 * - **禁止**在此提交本机/团队局域网 IP、内测域名或任何秘钥;
 * - 真机 `adb reverse tcp:8080 tcp:8080`、局域网联调等**本机地址**走 debug 专属
 *   SP 覆盖 (`MgmtPref.serverOverride`, 存设备私有 SharedPreferences, 天然不入库),
 *   不要修改本文件。
 */
object MgmtEnv {

    /** false = 开发环境 (debug 构建)。 */
    const val IS_PRODUCTION: Boolean = false

    /**
     * 开发服务器默认根地址 (无尾 `/`): 模拟器经 10.0.2.2 访问宿主机 dev nginx 统一入口
     * (8080 端口, `/api/v1`、`/ws/device` 同源同口)。
     */
    const val DEFAULT_BASE_URL: String = "http://10.0.2.2:8080"

    /** debug 允许 SP 本机覆盖 (真机 adb reverse / 局域网调试)。 */
    const val ALLOW_LOCAL_OVERRIDE: Boolean = true
}
