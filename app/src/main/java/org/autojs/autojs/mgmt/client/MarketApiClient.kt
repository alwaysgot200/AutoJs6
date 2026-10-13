package org.autojs.autojs.mgmt.client

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.autojs.autojs.mgmt.auth.AccountSession
import org.autojs.autojs.mgmt.core.DeviceFingerprint
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 脚本市场下载客户端 (方案 §10.1/§10.2-7, P3 免费脚本)。
 *
 * - 零新增依赖: OkHttp 为上游既有依赖; 与 [MgmtApiHttp] 的短超时 client 分离,
 *   下载单独放宽 readTimeout;
 * - 携带 JWT (已登录时; 免费包游客亦可下载) 与 **X-Device-Id** ([DeviceFingerprint],
 *   P3 仅留痕, P4 收费核销同值);
 * - 响应声明的 sha256/size 必须与落盘文件一致, 任一不符整包拒绝 (绝不安装);
 * - 非 2xx 统一解析后端信封 {code,msg}, 把业务文案 (7154/7158/7166/7167 等) 原样上抛,
 *   由 UI 层中文提示。
 *
 * 全部方法阻塞调用方线程, 调用方必须在 [MgmtApiHttp.executor] 上执行,
 * 回调经 [MgmtApiHttp.callOnMain] 切回主线程。
 */
internal object MarketApiClient {

    /** 下载完成的清洁包及其服务端声明元数据 (临时文件, 安装成功/失败后由安装器清理)。 */
    data class PackageFile(
        val file: File,
        val scriptId: Int,
        val installSlug: String,
        val packageType: String,
        val versionName: String,
        val versionCode: Int,
        val entryFile: String,
        val declaredSize: Long,
        val declaredHash: String,
    )

    /**
     * @param bizCode 后端统一信封业务码 (如 7154/7158/7166/7167); 网络层失败/HTTP 非 2xx
     *        且无信封时为 null, 供 UI 决定提示形态与日志定位
     */
    class DownloadException(val bizCode: Int?, message: String) : Exception(message) {
        constructor(message: String) : this(null, message)
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 阻塞下载 [scriptId] 的当前版本清洁包并完成校验。
     *
     * @throws DownloadException 业务拒绝/网络失败/校验不符 (message 为可直接展示的中文文案)
     */
    fun downloadBlocking(context: Context, scriptId: Int): PackageFile {
        val request = Request.Builder()
            .url(MgmtApiHttp.apiV1("/market/download/$scriptId"))
            .header("X-Device-Id", DeviceFingerprint.get())
            .apply {
                // 免费包无需登录; 已登录时带 JWT (P4 收费下载必需, P3 保持同一调用形态)。
                AccountSession.accessToken()?.takeIf { it.isNotEmpty() }?.let { token ->
                    header("Authorization", "Bearer $token")
                }
            }
            .get()
            .build()

        val response = runCatching { client.newCall(request).execute() }
            .getOrElse { throw DownloadException("网络异常: ${it.message ?: it.javaClass.simpleName}") }

        response.use { resp ->
            val body = resp.body
            if (!resp.isSuccessful || body == null) {
                val raw = runCatching { body?.string() }.getOrNull()
                val envelope = MgmtApiHttp.parseEnvelope(raw)
                val bizMsg = envelope?.msg
                // 业务拒绝 (7154/7158/7166/7167 等) 携带码值, UI 以指引对话框展示;
                // 无信封的网关/HTTP 错误 bizCode=null。
                throw DownloadException(
                    envelope?.code?.takeIf { it != 200 },
                    bizMsg ?: "服务暂不可用 (HTTP ${resp.code})",
                )
            }

            val headers = resp.headers
            val declaredHash = headers["x-file-hash"].orEmpty().trim().lowercase()
            val declaredSize = headers["x-file-size"]?.toLongOrNull() ?: -1L
            val packageType = headers["x-package-type"].orEmpty().trim().uppercase()
            val entryFile = headers["x-entry-file"].orEmpty().trim()
            val installSlug = headers["x-install-slug"].orEmpty().trim()
            val versionName = headers["x-version-name"].orEmpty().trim()
            val versionCode = headers["x-version-code"]?.toIntOrNull() ?: 0
            val headerScriptId = headers["x-script-id"]?.toIntOrNull() ?: scriptId

            if (declaredHash.length != 64 || packageType !in setOf("SINGLE", "PROJECT", "PLUGIN") ||
                entryFile.isEmpty() || installSlug.isEmpty()
            ) {
                throw DownloadException("下载响应缺少版本元数据, 请稍后重试")
            }

            // PLUGIN 落官方插件安装同一目录 cacheDir/plugin_dl/ (FileProvider root-path 已覆盖),
            // 且必须以 .apk 结尾供系统安装器识别; SINGLE/PROJECT 仍落 mgmt-market/*.zip。
            val isPlugin = packageType == "PLUGIN"
            val dir = if (isPlugin) {
                File(context.cacheDir, "plugin_dl")
            } else {
                File(context.cacheDir, "mgmt-market")
            }.apply { runCatching { mkdirs() } }
            val temp = if (isPlugin) {
                File(dir, pluginApkName(entryFile, headerScriptId))
            } else {
                File(dir, "dl-$scriptId-${System.nanoTime()}.zip")
            }
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read
                        }
                    }
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (declaredSize >= 0 && written != declaredSize) {
                    throw DownloadException("安装包大小与服务端声明不一致, 已拒绝安装")
                }
                if (!MessageDigest.isEqual(actualHash.toByteArray(), declaredHash.toByteArray())) {
                    throw DownloadException("安装包校验失败 (SHA-256 不一致), 已拒绝安装")
                }
                return PackageFile(
                    file = temp,
                    scriptId = headerScriptId,
                    installSlug = installSlug,
                    packageType = packageType,
                    versionName = versionName,
                    versionCode = versionCode,
                    entryFile = entryFile,
                    declaredSize = declaredSize,
                    declaredHash = declaredHash,
                )
            } catch (e: Exception) {
                // PLUGIN 下载失败可安全删除半成品 (成功时不删: 系统安装器需持续读取该文件);
                // SINGLE/PROJECT 维持原有失败即删临时 zip 的行为。
                runCatching { temp.delete() }
                if (e is DownloadException) throw e
                throw DownloadException("下载中断: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * PLUGIN 落盘文件名防护: 只信任响应头 entryFile 的 basename, 且必须匹配
     * `^[A-Za-z0-9._-]+\.apk$`、长度 ≤200; 任一不符回退 `market-<scriptId>.apk`
     * (与服务端 sanitizePluginName 同口径, 防路径注入/非法字符)。
     */
    private val pluginApkNamePattern = Regex("^[A-Za-z0-9._-]+\\.apk$", RegexOption.IGNORE_CASE)

    private fun pluginApkName(entryFile: String, scriptId: Int): String {
        val base = entryFile.substringAfterLast('/').substringAfterLast('\\')
        return if (base.length <= 200 && pluginApkNamePattern.matches(base)) base else "market-$scriptId.apk"
    }
}
