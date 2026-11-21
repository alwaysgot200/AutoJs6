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
import org.json.JSONObject
import java.io.File
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
            // 成功才记 token：工作目录未就绪等临时失败可由下次触发重试。
            runCatching { sync(token) }
                .onSuccess { syncedToken = token }
                .onFailure { Log.w(TAG, "library sync failed", it) }
        }
    }

    private fun sync(token: String) {
        val context = appContext ?: return
        reconcileLocal(token)
        val items = fetchLibrary(token)
        var restored = 0
        for (item in items) {
            if (restored >= MAX_RESTORE) break
            val scriptId = item.scriptId ?: continue
            if (item.packageType != "SINGLE" && item.packageType != "PROJECT") continue
            if (!item.available || !item.licensed) continue
            if (isInstalled(scriptId)) continue
            val pkg = MarketApiClient.downloadBlocking(context, scriptId)
            MarketScriptInstaller.installBlocking(pkg)
            restored++
        }
        if (restored > 0) Log.i(TAG, "restored $restored market script(s)")
    }

    /** 服务端库条目 (只取同步所需字段)。 */
    private data class LibraryItem(
        val scriptId: Int?,
        val packageType: String,
        val available: Boolean,
        val licensed: Boolean,
    )

    private fun fetchLibrary(token: String): List<LibraryItem> {
        val request = Request.Builder()
            .url(MgmtApiHttp.apiV1("/market/library?product=$PRODUCT"))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val body = runCatching {
            MgmtApiHttp.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull() ?: return emptyList()
        val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull()
            ?: return emptyList()
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

    /** 服务端安装短码约定为 `script-<id>` (MarketScriptInstaller 只信响应头)。 */
    private fun isInstalled(scriptId: Int): Boolean =
        File(File(WorkingDirectoryUtils.path, "market"), "script-$scriptId").isDirectory
}
