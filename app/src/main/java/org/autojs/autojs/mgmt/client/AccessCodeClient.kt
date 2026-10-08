package org.autojs.autojs.mgmt.client

import okhttp3.Request
import org.autojs.autojs.mgmt.client.MgmtApiHttp.apiV1
import org.autojs.autojs.mgmt.client.MgmtApiHttp.callOnMain
import org.autojs.autojs.mgmt.client.MgmtApiHttp.client
import org.autojs.autojs.mgmt.client.MgmtApiHttp.executor
import org.autojs.autojs.mgmt.client.MgmtApiHttp.parseEnvelope

/**
 * 我的设备接入码客户端 (方案 §7.2):
 *
 * 账号登录成功后调用 `GET /api/v1/device/access-code/mine` (仅需登录态, 任意租户成员
 * 含 employee 可读) 获取本租户 matchCode, 静默写入既有 secret SP 键
 * (`key_$_management_platform_secret`, 键名逐字符不变), 作为 /ws/device 握手凭证。
 *
 * 平台超管账号无业务接入码, 服务端返回业务码 7018;
 * 网络/鉴权失败由调用方按错误信息明确提示, 不得静默。
 */
object AccessCodeClient {

    /** 平台超管账号无业务接入码 (服务端 ResponseCode.DEVICE_ACCESS_CODE_PLATFORM_UNSUPPORTED)。 */
    const val BIZ_CODE_PLATFORM_UNSUPPORTED = 7018

    /** 会话令牌无效/过期 (HTTP 401 或信封业务码 401)。 */
    const val BIZ_CODE_UNAUTHORIZED = 401

    data class MyAccessCode(
        val matchCode: String,
        val tenantId: String,
        val updatedAt: String?,
    )

    /**
     * 拉取我的接入码。
     *
     * @return 成功 [Result] 携带 [MyAccessCode]; 失败 Result.exception 为
     * [AccessCodeException] (含业务码, 便于 UI 区分超管/过期/其他); 网络异常为原始异常。
     */
    fun fetchMine(accessToken: String, callback: (Result<MyAccessCode>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val request = Request.Builder()
                    .url(apiV1("/device/access-code/mine"))
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val envelope = parseEnvelope(response.body?.string())
                    if (response.code == BIZ_CODE_UNAUTHORIZED ||
                        envelope?.code == BIZ_CODE_UNAUTHORIZED
                    ) {
                        throw AccessCodeException(BIZ_CODE_UNAUTHORIZED, "登录已过期，请重新登录")
                    }
                    require(envelope != null) { "接入码响应解析失败 (HTTP ${response.code})" }
                    if (envelope.code != 200) {
                        throw AccessCodeException(envelope.code, envelope.msg ?: "接入码获取失败 (${envelope.code})")
                    }
                    val data = envelope.data ?: throw AccessCodeException(null, "接入码响应为空")
                    val matchCode = data.optString("matchCode").takeIf { it.isNotEmpty() }
                        ?: throw AccessCodeException(null, "接入码响应缺少 matchCode")
                    MyAccessCode(
                        matchCode = matchCode,
                        tenantId = data.optString("tenantId"),
                        updatedAt = data.optString("updatedAt").takeIf { it.isNotEmpty() },
                    )
                }
            }
            callOnMain(callback, result)
        }
    }

    /** 接入码业务失败 (HTTP 通常仍为 200, 以信封 code 区分)。 */
    class AccessCodeException(val bizCode: Int?, message: String) : Exception(message)
}
