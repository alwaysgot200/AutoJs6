package org.autojs.autojs.mgmt.auth

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.autojs.autojs.mgmt.client.MgmtApiHttp
import org.autojs.autojs.mgmt.client.MgmtApiHttp.apiV1
import org.autojs.autojs.mgmt.client.MgmtApiHttp.callOnMain
import org.autojs.autojs.mgmt.client.MgmtApiHttp.client
import org.autojs.autojs.mgmt.client.MgmtApiHttp.executor
import org.autojs.autojs.mgmt.client.MgmtApiHttp.parseEnvelope
import org.json.JSONObject

/**
 * 基座账号体系 HTTP 客户端 (方案 §7.1, 零新增认证通道):
 *
 * - GET  /auth/code   获取图形验证码 (数学表达式, SVG data URI; 开关关闭时不展示);
 * - POST /auth/login  租户账号密码登录, 返回 access_token / refresh_token;
 * - POST /auth/logout 令牌加入黑名单 (最佳努力, 失败不影响本地清理)。
 *
 * 设备端以 clientId=android 标识; 不加 isEncrypt 头, 服务端明文 JSON 校验
 * (与 AGENTS.md §13.1 浏览器冒烟同一通道)。
 */
internal object AccountApiClient {

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    data class Captcha(val enabled: Boolean, val uuid: String, val img: String)

    data class LoginTokens(
        val accessToken: String,
        val refreshToken: String,
        /** access_token 有效期秒数 (服务端缺省按 3600)。 */
        val expiresInSeconds: Long,
    )

    fun fetchCaptcha(callback: (Result<Captcha>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val request = Request.Builder().url(apiV1("/auth/code")).get().build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    val envelope = parseEnvelope(body)
                    require(envelope?.code == 200) { envelope?.msg ?: "验证码获取失败 (HTTP ${response.code})" }
                    val data = envelope.data ?: error("验证码响应为空")
                    Captcha(
                        enabled = data.optBoolean("captchaEnabled", false),
                        uuid = data.optString("uuid"),
                        img = data.optString("img"),
                    )
                }
            }
            callOnMain(callback, result)
        }
    }

    fun login(
        username: String,
        password: String,
        code: String,
        uuid: String,
        callback: (Result<LoginTokens>) -> Unit,
    ) {
        executor.execute {
            val result = runCatching {
                val payload = JSONObject()
                    .put("username", username)
                    .put("password", password)
                    .put("code", code)
                    .put("uuid", uuid)
                    .put("clientId", "android")
                    .toString()
                val request = Request.Builder()
                    .url(apiV1("/auth/login"))
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                client.newCall(request).execute().use { response ->
                    val envelope = parseEnvelope(response.body?.string())
                    require(envelope != null) { "登录响应解析失败 (HTTP ${response.code})" }
                    require(envelope.code == 200) { envelope.msg ?: "登录失败 (${envelope.code})" }
                    val data = envelope.data ?: error("登录响应为空")
                    val token = data.optString("access_token").takeIf { it.isNotEmpty() }
                        ?: error("登录响应缺少令牌")
                    LoginTokens(
                        accessToken = token,
                        refreshToken = data.optString("refresh_token").ifEmpty { token },
                        expiresInSeconds = data.optLong("expire_in", 3600L),
                    )
                }
            }
            callOnMain(callback, result)
        }
    }

    /** 退出登录: 最佳努力。任何网络错误都回调成功 (本地凭证随后照常清除)。 */
    fun logout(accessToken: String, callback: () -> Unit) {
        executor.execute {
            runCatching {
                val request = Request.Builder()
                    .url(apiV1("/auth/logout"))
                    .header("Authorization", "Bearer $accessToken")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
                client.newCall(request).execute().close()
            }
            org.autojs.autojs.mgmt.client.MgmtApiHttp.mainHandler.post { runCatching { callback() } }
        }
    }
}
