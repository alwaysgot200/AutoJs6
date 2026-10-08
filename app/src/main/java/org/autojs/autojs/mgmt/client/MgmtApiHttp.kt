package org.autojs.autojs.mgmt.client

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import org.autojs.autojs.mgmt.config.MgmtServerConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * mgmt 层 REST API 共享基础设施 (零新增依赖: OkHttp 为上游既有依赖)。
 *
 * - [client] 短时 HTTP 调用专用 (15s 超时), 与 ManagementPlatformClient 的长连接
 *   client 分离, 避免下载/登录等慢调用共享调度参数;
 * - [executor] 守护线程池, 全部网络调用在后台执行, 回调统一 post 主线程;
 * - 统一信封 { code, msg, data } 解析见 [parseEnvelope]。
 */
internal object MgmtApiHttp {

    val mainHandler: Handler = Handler(Looper.getMainLooper())

    val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "mgmt-api").apply { isDaemon = true }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** REST v1 全路径, 如 GET https://host/api/v1/device/access-code/mine。 */
    fun apiV1(path: String): String = "${MgmtServerConfig.apiBaseUrlV1()}${path.let { if (it.startsWith('/')) it else "/$it" }}"

    /**
     * 统一信封解析结果。
     *
     * @param code 业务码 (200 成功); HTTP 层失败 (网络错误/非 2xx) 时 code=null
     */
    data class Envelope(val code: Int, val msg: String?, val data: org.json.JSONObject?)

    fun parseEnvelope(body: String?): Envelope? = runCatching {
        val json = org.json.JSONObject(body.orEmpty())
        Envelope(
            json.optInt("code", -1),
            json.optString("msg").takeIf { it.isNotEmpty() },
            json.optJSONObject("data"),
        )
    }.getOrNull()

    fun <T> callOnMain(callback: (Result<T>) -> Unit, result: Result<T>) {
        mainHandler.post { runCatching { callback(result) } }
    }
}
