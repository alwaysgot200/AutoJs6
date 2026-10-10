package org.autojs.autojs.mgmt.config

/**
 * 生产环境接入配置 —— 仅编译进 release 构建类型 (appRelease / inrtRelease)。
 *
 * CI 发版 (`deploy.yml` 的 build-android-client) 与手动触发产出的预览 APK 均为
 * release 变体, 一律连接生产环境 (开发/生产一致性, 见业务仓 AGENTS.md §13.2:
 * 不允许 release 产物存在 dev 专用行为)。
 *
 * Git 安全 (fork 为公开开源仓库):
 * - 这里仅存放对外公开的服务域名 (本就是公开服务, 出厂 APK 内同样可见), 不是秘钥;
 * - [ALLOW_LOCAL_OVERRIDE] = false: 设备上残留的历史 SP 地址 (老版本/调试写入)
 *   在 release 构建中一律不读, 保证生产包只可能连到生产服务器;
 * - 客户端无任何内置秘钥: 身份凭证为用户登录后获得的 JWT 与租户接入码, 均存设备私有存储。
 */
object MgmtEnv {

    /** true = 生产环境 (release 构建)。 */
    const val IS_PRODUCTION: Boolean = true

    /** 生产环境统一入口 (无尾 `/`; nginx: `/`、`/admin/`、`/api/v1`、`/ws/device` 同源)。 */
    const val DEFAULT_BASE_URL: String = "https://autojs6.80fafa.com"

    /** release 禁止任何本机/SP 覆盖, 杜绝调试地址误入生产包。 */
    const val ALLOW_LOCAL_OVERRIDE: Boolean = false
}
