package org.autojs.autojs.mgmt.auth

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.autojs.autojs.mgmt.client.AccessCodeClient
import org.autojs.autojs.mgmt.pref.MgmtPref

/**
 * 账号会话 (方案 §10.1/§10.2):
 *
 * - 登录走基座唯一账号体系 (/auth/login, 账号+密码+图形验证码), 不自造任何注册/认证通道;
 * - JWT (access/refresh) 经 [AccountTokenStore] 用 AndroidKeyStore 加密持久化,
 *   App 重启/进程被杀自动恢复, 即"微信式长久登录";
 * - 登录成功后自动调 /device/access-code/mine 拉取本租户 matchCode 写入 secret 键,
 *   用户无需手填地址/接入码;
 * - 只有 [logout] 清除全部凭证 (令牌 + matchCode), 调用方负责同时断开 WS;
 * - 设备连接与会话独立: 已登录未连接可逛市场/购买, 连接仅由用户手动发起。
 *
 * 本类只管账号状态, 不直接触碰 WS/Service (Mgmt 门面经 [Listener] 感知状态变化)。
 */
object AccountSession {

    private const val TAG = "MgmtAccount"

    fun interface Listener {
        /** @param loggedIn true=登录完成/会话恢复; false=退出登录或会话失效 */
        fun onAccountStateChanged(loggedIn: Boolean)
    }

    /**
     * 登录编排结果。
     *
     * @param accessCodeReady true=接入码已就绪可发起设备连接;
     *        false=账号登录成功但接入码拉取失败 (网络/超管账号), 会话保留,
     *        用户点「连接设备」时经 [ensureAccessCode] 重试。
     */
    data class LoginResult(val accessCodeReady: Boolean, val notice: String?)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = java.util.Collections.synchronizedList(mutableListOf<Listener>())

    @Volatile
    private var username: String? = null

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var refreshToken: String? = null

    @Volatile
    private var expireAtMillis: Long = 0L

    val isLoggedIn: Boolean
        get() = !accessToken.isNullOrEmpty()

    /** 当前登录账号名 (登录输入的 username), 未登录为 null。 */
    fun username(): String? = username

    fun accessToken(): String? = accessToken

    /** 当前会话 refresh token（供市场 WebView 桥同步，与基座浏览器端存储形态一致）。 */
    fun refreshToken(): String? = refreshToken

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    /**
     * 进程启动时从加密 SP 恢复会话 (H2 bootstrap 调用, 纯本地 IO 但内部吞异常)。
     */
    fun restore() {
        runCatching {
            val saved = AccountTokenStore.load()
            if (saved != null) {
                applySaved(saved)
                MgmtPref.sessionEnabled = true
                publishLoggedIn()
                Log.i(TAG, "account session restored for ${saved.username}")
            }
        }.onFailure { Log.w(TAG, "restore session failed", it) }
    }

    /**
     * 完整登录: 令牌登录 → 持久化 → 拉取接入码。回调在主线程。
     *
     * 账号/密码/验证码错误: Result.failure, 不建立会话;
     * 接入码拉取失败 (网络错误/7018 超管): 会话已建立, Result.success(accessCodeReady=false)。
     */
    fun login(
        usernameInput: String,
        password: String,
        code: String,
        uuid: String,
        callback: (Result<LoginResult>) -> Unit,
    ) {
        val username = usernameInput.trim()
        AccountApiClient.login(username, password, code.trim(), uuid) { loginResult ->
            val tokens = loginResult.getOrElse {
                callback(Result.failure(it))
                return@login
            }
            val expireAt = System.currentTimeMillis() + (tokens.expiresInSeconds.coerceAtLeast(0L)) * 1000L
            runCatching {
                AccountTokenStore.save(username, tokens.accessToken, tokens.refreshToken, expireAt)
                applySaved(AccountTokenStore.load()!!)
            }.onFailure {
                callback(Result.failure(it))
                return@login
            }
            MgmtPref.sessionEnabled = true
            fetchAccessCodeAfterLogin(callback)
        }
    }

    /**
     * 已登录但接入码缺失/拉取失败时, 「连接设备」动作触发重试。回调在主线程。
     */
    fun ensureAccessCode(callback: (Result<Unit>) -> Unit) {
        val token = accessToken
        if (token.isNullOrEmpty()) {
            callback(Result.failure(IllegalStateException("未登录")))
            return
        }
        AccessCodeClient.fetchMine(token) { result ->
            val outcome = result.mapCatching { mine ->
                require(mine.matchCode.isNotEmpty()) { "服务端返回空接入码" }
                MgmtPref.secret = mine.matchCode
                Log.i(TAG, "access code provisioned for tenant ${mine.tenantId}")
                Unit
            }
            callback(outcome)
        }
    }

    /**
     * 退出登录: 通知服务端拉黑令牌 (最佳努力) → 清除全部本地凭证 (令牌 + matchCode)。
     * 完成后回调 (主线程), 调用方随后断开 WS/停止前台服务。
     */
    fun logout(onDone: () -> Unit) {
        val token = accessToken
        if (token.isNullOrEmpty()) {
            clearLocalCredentials()
            publishLoggedOut()
            onDone()
            return
        }
        AccountApiClient.logout(token) {
            clearLocalCredentials()
            publishLoggedOut()
            onDone()
        }
    }

    /**
     * 会话被服务端判失效 (401): 清理并通知 UI 回到未登录态 (供后续市场 HTTP/WebView 使用)。
     */
    fun notifyExpired() {
        clearLocalCredentials()
        publishLoggedOut()
    }

    private fun fetchAccessCodeAfterLogin(callback: (Result<LoginResult>) -> Unit) {
        val token = accessToken ?: run {
            callback(Result.failure(IllegalStateException("会话未建立")))
            return
        }
        AccessCodeClient.fetchMine(token) { result ->
            result
                .onSuccess { mine ->
                    MgmtPref.secret = mine.matchCode
                    publishLoggedIn()
                    callback(Result.success(LoginResult(accessCodeReady = true, notice = null)))
                }
                .onFailure { err ->
                    // 账号登录已成功: 保留会话, 由 UI toast 具体原因; 连接设备时可重试拉取。
                    if (err is AccessCodeClient.AccessCodeException &&
                        err.bizCode == AccessCodeClient.BIZ_CODE_UNAUTHORIZED
                    ) {
                        // 刚登录即 401 属于异常态, 不保留会话
                        clearLocalCredentials()
                        publishLoggedOut()
                        callback(Result.failure(err))
                        return@onFailure
                    }
                    Log.w(TAG, "access code fetch failed after login", err)
                    publishLoggedIn()
                    callback(Result.success(LoginResult(accessCodeReady = false, notice = err.message ?: "接入码获取失败")))
                }
        }
    }

    private fun applySaved(saved: AccountTokenStore.SavedSession) {
        username = saved.username
        accessToken = saved.accessToken
        refreshToken = saved.refreshToken
        expireAtMillis = saved.expireAtMillis
    }

    private fun clearLocalCredentials() {
        runCatching { AccountTokenStore.clear() }
        username = null
        accessToken = null
        refreshToken = null
        expireAtMillis = 0L
        MgmtPref.sessionEnabled = false
        // 安全: 连同租户接入码一并清除, 防止下一个登录账号网络失败时沿用上一租户 matchCode。
        MgmtPref.secret = ""
    }

    private fun publishLoggedIn() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        mainHandler.post { snapshot.forEach { runCatching { it.onAccountStateChanged(true) } } }
    }

    private fun publishLoggedOut() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        mainHandler.post { snapshot.forEach { runCatching { it.onAccountStateChanged(false) } } }
    }
}
