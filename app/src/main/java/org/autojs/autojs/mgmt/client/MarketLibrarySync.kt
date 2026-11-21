package org.autojs.autojs.mgmt.client

import android.app.Application
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.autojs.autojs.mgmt.auth.AccountSession
import org.autojs.autojs.mgmt.script.MarketScriptInstaller
import org.autojs.autojs.util.WorkingDirectoryUtils
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * 「我的库」客户端同步 (多产品方案 §4.10.3, P7):
 *
 * - 登录/会话恢复后拉取 `GET /market/library?product=autojs6`;
 * - 把本机 `market/script-*` 既有安装 reconcile 回服务端 (兼容 P6 之前的下载);
 * - 缺失的 SINGLE/PROJECT 脚本自动恢复安装; PLUGIN 不在后台自动装
 *   (由 Web「我的库」/详情页的点选安装经系统安装器确认), 论坛附件不入库;
 * - 全部网络/IO 在单线程守护线程执行, 失败只留日志, 不影响宿主。
 */
internal object MarketLibrarySync {

    private const val TAG = "MgmtLibrarySync"
    private const val PRODUCT = "autojs6"
    private const val MAX_RECONCILE = 200
    private const val MAX_RESTORE = 20
    private const val JSON = "application/json; charset=utf-8"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mgmt-library-sync").apply { isDaemon = true }
    }

    @Volatile
    private var appContext: Application? = null

    @Volatile
    private var syncedToken: String? = null

    /** 在 `Mgmt.bootstrap` 调用一次: 监听登录/恢复并触发首次同步。 */
    fun bootstrap(application: Application) {
        if (appContext != null) return
        appContext = application
        AccountSession.addListener { loggedIn ->
            if (loggedIn) requestSync(delayMs = 5_000L)
        }
        if (AccountSession.isLoggedIn) requestSync(delayMs = 8_000L)
    }

    /**
     * 排队一次同步; 延迟用于避开启动/登录后的主线程高峰,
     * 同一 token 在本进程只同步一次 (重新登录会换 token)。
     */
    fun requestSync(delayMs: Long = 0L) {
        val token = AccountSession.accessToken() ?: return
        if (appContext == null) return
        executor.execute {
            if (delayMs > 0L) runCatching { Thread.sleep(delayMs) }
            val current = AccountSession.accessToken()
            if (current.isNullOrEmpty() || current != token) return@execute
            if (syncedToken == token) return@execute
            // 只有整轮同步完成才记 token: 拉取失败/单项恢复失败/恢复被截断都不算完成,
            // 不记账, 由下次触发续跑 (工作目录未就绪等临时失败同理)。
            runCatching { sync(token) }
                .onSuccess { syncedToken = token }
                .onFailure { Log.w(TAG, "library sync failed", it) }
        }
    }

    /**
     * 整轮同步: 对账 → 拉库 → 逐项恢复。
     *
     * - 拉取失败直接抛出 (失败绝不能被记成已同步, 见 [fetchLibrary]);
     * - 单项恢复失败只计数留日志, 不阻断其余项;
     * - 有失败项或被 [MAX_RESTORE] 截断时整体抛 [SyncIncompleteException],
     *   调用方不记 syncedToken, 下次触发续跑剩余项。
     */
    private fun sync(token: String) {
        val context = appContext ?: return
        reconcileLocal(token)
        val items = fetchLibrary(token)
        var restored = 0
        var failed = 0
        var truncated = false
        for (item in items) {
            val scriptId = item.scriptId ?: continue
            if (item.packageType != "SINGLE" && item.packageType != "PROJECT") continue
            if (!item.available || !item.licensed) continue
            if (isInstalled(scriptId)) continue
            if (restored >= MAX_RESTORE) {
                // 截断点后仍有待恢复项: 本轮不记账, 下次触发继续。
                truncated = true
                break
            }
            runCatching {
                val pkg = MarketApiClient.downloadBlocking(context, scriptId)
                MarketScriptInstaller.installBlocking(pkg)
            }.onSuccess {
                restored++
            }.onFailure {
                failed++
                Log.w(TAG, "restore failed for script-$scriptId", it)
            }
        }
        if (restored > 0 || failed > 0) {
            Log.i(TAG, "restore pass done: restored=$restored failed=$failed truncated=$truncated")
        }
        if (truncated) {
            Log.w(TAG, "restore truncated at MAX_RESTORE=$MAX_RESTORE with pending items left, continue on next trigger")
        }
        if (truncated || failed > 0) {
            throw SyncIncompleteException(
                "library restore incomplete (restored=$restored failed=$failed truncated=$truncated)",
            )
        }
    }

    /** 单轮同步未完成 (存在失败项或被 MAX_RESTORE 截断): 抛出使调用方不记 syncedToken。 */
    private class SyncIncompleteException(message: String) : Exception(message)

    /** 服务端库条目 (只取同步所需字段)。 */
    private data class LibraryItem(
        val scriptId: Int?,
        val packageType: String,
        val available: Boolean,
        val licensed: Boolean,
    )

    /**
     * 拉取「我的库」。
     *
     * 网络失败/HTTP 非 2xx/信封不合法一律抛出 (S3): 若按空库吞掉, 调用方会把失败当成功
     * 记 syncedToken, 一次瞬时断网就永久放弃跨设备恢复。只有 `data` 为合法数组
     * (含空数组) 才是空库。失败原因经 `requestSync` 的 `Log.w` 留痕。
     *
     * @throws IOException 网络错误/HTTP 非 2xx/业务码非 200/响应体不合法
     */
    private fun fetchLibrary(token: String): List<LibraryItem> {
        val request = Request.Builder()
            .url(MgmtApiHttp.apiV1("/market/library?product=$PRODUCT"))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val body = MgmtApiHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("library fetch rejected: HTTP ${response.code}")
            }
            response.body?.string() ?: throw IOException("library fetch returned empty body")
        }
        val json = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw IOException("library response is not valid JSON", e)
        }
        val code = json.optInt("code", -1)
        if (code != 200) {
            throw IOException("library fetch rejected: code=$code msg=${json.optString("msg")}")
        }
        val data = json.optJSONArray("data")
            ?: throw IOException("library response missing data array")
        return buildList {
            for (index in 0 until data.length()) {
                val obj = data.optJSONObject(index) ?: continue
                add(
                    LibraryItem(
                        scriptId = obj.optInt("scriptId", 0).takeIf { it > 0 },
                        packageType = obj.optString("packageType"),
                        available = obj.optBoolean("available"),
                        licensed = obj.optBoolean("licensed"),
                    ),
                )
            }
        }
    }

    /**
     * 把本机市场安装目录的 scriptId 报给服务端 (只补录, 不触发下载)。
     * 旧版本下载过的脚本因此出现在「我的库」。
     */
    private fun reconcileLocal(token: String) {
        val marketRoot = File(WorkingDirectoryUtils.path, "market")
        val ids = marketRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                Regex("^script-(\\d+)$").find(dir.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            ?.distinct()
            ?.take(MAX_RECONCILE)
            .orEmpty()
        if (ids.isEmpty()) return
        val items = JSONArray().apply {
            ids.forEach { id ->
                put(JSONObject().put("scriptId", id).put("source", "RECONCILE"))
            }
        }
        val payload = JSONObject()
            .put("product", PRODUCT)
            .put("items", items)
            .toString()
        val request = Request.Builder()
            .url(MgmtApiHttp.apiV1("/market/library/reconcile"))
            .header("Authorization", "Bearer $token")
            .post(payload.toRequestBody(JSON.toMediaType()))
            .build()
        runCatching {
            MgmtApiHttp.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) Log.w(TAG, "reconcile rejected: HTTP ${response.code}")
            }
        }.onFailure { Log.w(TAG, "reconcile failed", it) }
    }

    /**
     * 服务端安装短码约定为 `script-<id>` (MarketScriptInstaller 只信响应头)。
     *
     * 目录存在 ≠ 安装完好: 崩溃/断电留下的坏目录若按已安装处理会永久丢恢复, 故进一步校验:
     * - PROJECT 安装必有 project.json (安装器校验后原子落盘), 其 main 入口必须真实存在;
     * - SINGLE 入口文件名只存于服务端响应头, 本机无从得知, 退化为「目录内至少有一个
     *   非空文件」的弱校验 (排除安装器遗留的 `.xxx.install-*.tmp` 临时文件)。
     */
    private fun isInstalled(scriptId: Int): Boolean {
        val dir = File(File(WorkingDirectoryUtils.path, "market"), "script-$scriptId")
        if (!dir.isDirectory) return false
        val projectJson = File(dir, "project.json")
        if (projectJson.isFile) {
            val main = runCatching { JSONObject(projectJson.readText()).optString("main") }
                .getOrNull()
                .orEmpty()
            if (main.isEmpty()) return false
            val mainFile = File(dir, main)
            return mainFile.isFile && mainFile.length() > 0L
        }
        return dir.walkTopDown().any { it.isFile && it.length() > 0L && !it.name.startsWith(".") }
    }
}
