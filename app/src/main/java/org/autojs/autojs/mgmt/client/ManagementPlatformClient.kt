package org.autojs.autojs.mgmt.client

import android.accessibilityservice.AccessibilityService as AndroidAccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.Display
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.autojs.autojs.AutoJs
import org.autojs.autojs.app.GlobalAppContext
import org.autojs.autojs.core.accessibility.AccessibilityService as AutoJsAccessibilityService
import org.autojs.autojs.mgmt.pref.MgmtPref
import org.autojs.autojs.execution.ExecutionConfig
import org.autojs.autojs.execution.ScriptExecution
import org.autojs.autojs.model.script.ScriptFile
import org.autojs.autojs.pio.PFiles
import org.autojs.autojs.runtime.ScriptRuntime
import org.autojs.autojs.runtime.api.AppUtils
import org.autojs.autojs.runtime.api.Device
import org.autojs.autojs.script.ScriptSource
import org.autojs.autojs.timing.IntentTask
import org.autojs.autojs.timing.TimedTask
import org.autojs.autojs.timing.TimedTaskManager
import org.autojs.autojs.external.fileprovider.AppFileProvider
import org.autojs.autojs.util.IntentUtils
import org.autojs.autojs.util.IntentUtils.ToastExceptionHolder
import org.autojs.autojs.util.RootUtils
import org.autojs.autojs.external.receiver.DynamicBroadcastReceivers
import org.autojs.autojs.util.WorkingDirectoryUtils
import org.autojs.autojs6.BuildConfig
import org.joda.time.DateTime
import org.joda.time.DateTimeConstants
import org.joda.time.LocalDateTime
import org.joda.time.LocalTime
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object ManagementPlatformClient {

    private const val TAG = "ManagementPlatformClient"

    /**
     * 连接状态监听器 (三态, 2026-09-29 跨境弱网优化):
     * 回调统一 post 到主线程, 调用方无需自行切线程; 注册即回放当前状态。
     */
    fun interface ConnectionStateListener {
        fun onConnectionStateChanged(status: ConnectionStatus)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectionListeners = java.util.Collections.synchronizedList(mutableListOf<ConnectionStateListener>())

    /**
     * 正式长连接是否已通过服务端鉴权 (收到 AUTH_OK)。
     * 注意: TCP/WS 握手成功 (onOpen) 不算已连接, 必须等应用层 AUTH_OK。
     */
    val isConnected: Boolean
        get() = status.state == ConnectionState.CONNECTED

    /**
     * 当前连接状态快照 (三态 + 原因 + 次数 + 是否升级提示)。
     */
    @Volatile
    var status: ConnectionStatus = ConnectionStatus.INITIAL
        private set

    /**
     * 提示升级调度任务: 进入 RECOVERING 后分别在 [QUIET_WINDOW_MS] 与 [ESCALATE_DISCONNECT_MS]
     * 触发——前者允许展示"自动重连中", 后者升级为 DISCONNECTED 并给出具体原因文案。
     */
    @Volatile
    private var quietWindowFuture: ScheduledFuture<*>? = null

    @Volatile
    private var escalateFuture: ScheduledFuture<*>? = null

    /**
     * 最近一次 AUTH_OK 携带的租户标识/名称。tenantName 为服务端 2026-09-28 起追加的可选字段,
     * 缺失时 UI 回落展示 tenantId; 断线/退出登录后清空。
     */
    @Volatile
    var tenantId: String? = null
        private set

    @Volatile
    var tenantName: String? = null
        private set

    /**
     * 最近一次连接是否被服务端明确拒绝 (收到 AUTH_FAILED 文本)。
     * 关闭帧可能因网络竞态丢失 (客户端表现为 onFailure 且拿不到 4001),
     * 因此应用层 AUTH_FAILED 也要作为慢速重试的判据。
     */
    @Volatile
    private var authRejected: Boolean = false

    fun addConnectionStateListener(listener: ConnectionStateListener) {
        synchronized(connectionListeners) {
            if (!connectionListeners.contains(listener)) {
                connectionListeners.add(listener)
            }
        }
        // 注册即回放当前状态, 避免 UI 先订阅后等事件
        mainHandler.post { listener.onConnectionStateChanged(status) }
    }

    fun removeConnectionStateListener(listener: ConnectionStateListener) {
        synchronized(connectionListeners) { connectionListeners.remove(listener) }
    }

    /**
     * 发布新状态 (无变化则跳过), 并把回调统一 post 到主线程, 单个监听器异常不影响其余订阅者。
     * 持对象锁串行化: 状态发布来自 OkHttp 回调线程、scheduler 线程、网络 binder 线程,
     * 不串行化会出现"已恢复 CONNECTED 之后又被并发的升级任务刷回 DISCONNECTED"的乱序。
     */
    @Synchronized
    private fun publishStatus(newStatus: ConnectionStatus) {
        if (status == newStatus) return
        status = newStatus
        val snapshot = synchronized(connectionListeners) { connectionListeners.toList() }
        mainHandler.post {
            snapshot.forEach { runCatching { it.onConnectionStateChanged(newStatus) } }
        }
    }

    /**
     * 鉴权成功 (AUTH_OK): 进入 CONNECTED, 取消所有提示升级任务, 归零计数。
     */
    @Synchronized
    private fun onAuthenticated() {
        cancelStatusEscalation()
        publishStatus(ConnectionStatus(ConnectionState.CONNECTED, ConnectionIssue.NONE, 0, false))
        // 鉴权通过后再重放 outbox（而非 onOpen）: 服务端去重窗口以已鉴权连接为准,
        // 鉴权失败的测试连接不会投送任何积压消息。
        OutboxManager.flush { envelope ->
            runCatching { webSocket?.send(envelope) == true }.getOrDefault(false)
        }
    }

    /**
     * 复位为安静的未连接态 (用户退出/配置失效), 取消提示升级链。
     */
    @Synchronized
    private fun resetDisconnected() {
        cancelStatusEscalation()
        publishStatus(
            ConnectionStatus(ConnectionState.DISCONNECTED, ConnectionIssue.NONE, 0, false),
        )
    }

    /**
     * 检测到连接断开 (onFailure/onClosed):
     * - 若此前为 CONNECTED: 进入 RECOVERING (安静), 并武装两个提示升级任务;
     * - 若已在 RECOVERING/DISCONNECTED (重试又失败): 不改变状态、不重建升级任务,
     *   DISCONNECTED 下保持原因文案稳定, 避免提示随每次重试来回翻转。
     */
    @Synchronized
    private fun onConnectionLost() {
        if (status.state == ConnectionState.CONNECTED) {
            enterRecovering(alreadyEscalated = false)
        }
    }

    /**
     * 进入重连中状态并武装提示升级链:
     * 安静窗口 [QUIET_WINDOW_MS] 内对用户完全静默; 超时仍未恢复则允许展示"自动重连中";
     * 再过 [ESCALATE_DISCONNECT_MS] 仍未恢复则升级为 DISCONNECTED + 具体原因。
     */
    @Synchronized
    private fun enterRecovering(alreadyEscalated: Boolean) {
        cancelStatusEscalation()
        if (alreadyEscalated) {
            // 由"已升级提示"态发起的新一轮尝试 (如网络恢复触发即时重连):
            // 保持提示可见但不重建安静窗口, 避免提示闪烁; 若本次尝试长时间未成功,
            // 兜底落回 DISCONNECTED 保持文案稳定。
            publishStatus(
                ConnectionStatus(ConnectionState.RECOVERING, currentIssue(), reconnectAttempts, true),
            )
            escalateFuture = scheduler.schedule({
                // 与 onAuthenticated 持同一把锁复检: 任务可能恰好在 AUTH_OK 到达时触发,
                // cancel(false) 无法中断已开始执行的任务, 必须在锁内确认状态未翻转。
                synchronized(this) {
                    if (status.state == ConnectionState.RECOVERING) {
                        publishStatus(
                            ConnectionStatus(
                                ConnectionState.DISCONNECTED,
                                currentIssue(),
                                reconnectAttempts,
                                true,
                            ),
                        )
                    }
                }
            }, RE_ESCALATE_AFTER_MS, TimeUnit.MILLISECONDS)
            return
        }
        publishStatus(
            ConnectionStatus(ConnectionState.RECOVERING, ConnectionIssue.NONE, reconnectAttempts, false),
        )
        quietWindowFuture = scheduler.schedule({
            synchronized(this) {
                if (status.state == ConnectionState.RECOVERING && !status.escalated) {
                    publishStatus(status.copy(escalated = true))
                }
            }
        }, QUIET_WINDOW_MS, TimeUnit.MILLISECONDS)
        escalateFuture = scheduler.schedule({
            synchronized(this) {
                if (status.state == ConnectionState.RECOVERING) {
                    publishStatus(
                        ConnectionStatus(
                            ConnectionState.DISCONNECTED,
                            currentIssue(),
                            reconnectAttempts,
                            true,
                        ),
                    )
                }
            }
        }, QUIET_WINDOW_MS + ESCALATE_DISCONNECT_MS, TimeUnit.MILLISECONDS)
    }

    private fun cancelStatusEscalation() {
        quietWindowFuture?.cancel(false)
        quietWindowFuture = null
        escalateFuture?.cancel(false)
        escalateFuture = null
    }

    /**
     * 评估当前中断原因: 本机无网优先, 其次鉴权拒绝, 最后视为服务器不可达。
     */
    private fun currentIssue(): ConnectionIssue = when {
        !NetworkReachabilityMonitor.currentlyOnline() -> ConnectionIssue.LOCAL_NETWORK_LOST
        authRejected -> ConnectionIssue.AUTH_REJECTED
        else -> ConnectionIssue.SERVER_UNREACHABLE
    }

    /**
     * 本机网络状态变化回调 (NetworkReachabilityMonitor):
     * - 网络恢复且当前未连接: 取消退避任务, 立即发起重连 (恢复延迟 P95 目标 ≤5s);
     * - 网络丢失且当前已处于升级提示态: 按最新原因刷新文案。
     */
    @JvmStatic
    @Synchronized
    fun onLocalNetworkStateChanged(online: Boolean) {
        if (online) {
            if (status.state != ConnectionState.CONNECTED) {
                immediateReconnect()
            }
        } else if (status.escalated && status.issue != ConnectionIssue.LOCAL_NETWORK_LOST) {
            publishStatus(status.copy(issue = ConnectionIssue.LOCAL_NETWORK_LOST))
        }
    }

    /**
     * 绕过幂等守卫立即重连: 先取消待执行的退避任务 (否则 connect() 的
     * "pending future" 守卫会把本次调用自己挡掉), 再走标准 connect 流程。
     */
    @Synchronized
    private fun immediateReconnect() {
        val address = MgmtPref.serverAddress.trim()
        val secret = MgmtPref.secret.trim()
        if (address.isEmpty() || secret.isEmpty() || !MgmtPref.sessionEnabled) {
            return
        }
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        Log.i(TAG, "local network available, reconnecting immediately")
        connect(address)
    }

    private val appContext get() = GlobalAppContext.get()

    // pingInterval: 让 OkHttp 每 20s 发 WebSocket 协议层 ping,
    // NAT 静默断网/拔网线时无 TCP 事件, 靠它快速发现死连接 (配合服务端 120s 心跳超时)
    // connectTimeout: 跨境高 RTT 下握手兜底; readTimeout=0: WS 存活只靠 ping/心跳,
    // 不被读取超时误杀; retryOnConnectionFailure: 握手阶段瞬时故障自动重试。
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    /**
     * root 命令专用线程池: execRoot 的 waitFor 与超时 kill 必须能并发
     * (scheduler 是单线程的, 阻塞在 waitFor 时超时任务永远排不上)。
     */
    private val rootExecutor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "mgmt-root-exec").apply { isDaemon = true }
    }

    /**
     * APK 下载/安装专用单线程 (2026-09-29 弱网加固):
     * INSTALL_APK 含跨境同步 HTTP 下载 (数十 MB) 与 `pm install` 等待, 此前跑在
     * [scheduler] 上会阻塞心跳/退避/状态升级所有定时任务——下载期间心跳停发,
     * 设备会被服务端 120s 超时判死且无法即时重连。独立线程隔离, 与连接生命周期解耦。
     */
    private val installExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mgmt-apk-install").apply { isDaemon = true }
    }

    /**
     * 截图专用单线程：takeScreenshot 回调、Bitmap 拷贝与 JPEG 编码全部移出主线程，
     * 串行执行保证帧顺序，避免连续抓帧卡顿无障碍主线程（远程截图方案 A 主线程卸载）。
     */
    private val shotThread: HandlerThread = HandlerThread("mgmt-shot").apply { isDaemon = true }
    private val shotHandler: Handler by lazy {
        shotThread.start()
        Handler(shotThread.looper)
    }
    private val shotExecutor: Executor = Executor { command -> shotHandler.post(command) }

    /**
     * 截图是否走二进制媒体帧（远程截图方案 A）。新服务端同时支持二进制/旧文本两通道，
     * 灰度期保留此开关；待新 APK 全量覆盖后，旧文本 SCREENSHOT 由独立清理 PR 删除。
     */
    private const val SCREENSHOT_BINARY_ENABLED = true
    private const val MEDIA_BINARY_MAGIC: Int = 0xA6
    private const val MEDIA_BINARY_VERSION: Int = 0x01
    private const val MEDIA_MSG_JPEG: Int = 0x01
    private const val FRAME_FLAG_KEYFRAME: Int = 0x01
    private const val MEDIA_HEADER_SIZE = 24

    /**
     * 系统无障碍截图最小间隔限流 (errorCode=3 ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT，
     * Android 16/API36 模拟器实测值；旧资料误记为 1，一律以符号常量为准) 的退避重试：
     * 连续/过快抓帧时系统直接拒绝，按 350ms 线性退避最多重试 3 次；重试期间 in-flight 保持，
     * 聚合到的 commandId 最终随成功帧/失败原因一起回执。
     */
    private const val SCREENSHOT_MAX_ATTEMPTS = 3
    private const val SCREENSHOT_RATE_LIMIT_RETRY_BASE_MS = 350L

    /** 媒体帧序号（截图/投屏共用，单调递增，跳过 0，回绕允许），服务端据此丢弃过期帧。 */
    private var mediaSequence: Int = 0

    @Synchronized
    private fun nextMediaSequence(): Int {
        mediaSequence = (mediaSequence + 1) and 0x7FFFFFFF
        if (mediaSequence == 0) mediaSequence = 1
        return mediaSequence
    }

    /** 抓帧进行中又收到的 REQUEST_SCREENSHOT，聚合其 commandId，复用本次抓帧结果统一回执，杜绝并发系统截图堆积。 */
    @Volatile
    private var screenshotInFlight: Boolean = false
    private val pendingScreenshotCommands = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var heartbeatFuture: ScheduledFuture<*>? = null

    @Volatile
    private var reconnectFuture: ScheduledFuture<*>? = null

    // 连续失败次数, onOpen 成功后归零; 用于指数退避
    @Volatile
    private var reconnectAttempts: Int = 0

    // 当前连接生命周期标记与连接签名: 启动时 Service 与粘性广播会并发触发 connect,
    // 必须幂等——同配置的进行中/已建立连接直接跳过, 否则两条 socket 互相 cancel 抖动。
    @Volatile
    private var connectionActive: Boolean = false

    @Volatile
    private var lastConnectKey: String? = null

    // 鉴权失败 (4001) 时的慢速重试间隔秒数, 避免错误接入码打爆服务端日志
    private const val AUTH_FAIL_RETRY_SECONDS = 60L
    private const val MAX_BACKOFF_SECONDS = 60L

    // 断线后的"安静窗口": 此期间多数跨境抖动可自愈, 对用户完全静默。
    private const val QUIET_WINDOW_MS = 10_000L

    // 安静窗口结束后再过多久仍未恢复, 就升级为 DISCONNECTED 并展示具体原因
    // (即断线后 30s: 10s 静默 + 20s "自动重连中")。
    private const val ESCALATE_DISCONNECT_MS = 20_000L

    // 已升级提示态下发起新连接尝试后, 多久仍未成功则兜底落回 DISCONNECTED。
    private const val RE_ESCALATE_AFTER_MS = 30_000L

    private data class ScriptLogRange(var startIndex: Int, var endIndex: Int? = null)

    private const val GLOBAL_LOG_ID = "__ALL__"
    private const val DEFAULT_LOG_TAIL_LINES = 200

    private val scriptLogRanges = mutableMapOf<String, ScriptLogRange>()

    @Volatile
    private var lastScreenshotImageWidth: Int = 0

    @Volatile
    private var lastScreenshotImageHeight: Int = 0

    @Volatile
    private var lastScreenWidth: Int = 0

    @Volatile
    private var lastScreenHeight: Int = 0

    /**
     * 设备能力快照 (随 DEVICE_INFO/HEARTBEAT 上报, 状态变化时主动推 CAPABILITIES):
     * accessibility = 无障碍服务已连接 (截图/全局按键/手势的前提)
     * storage       = 已授予"所有文件访问" (脚本落盘的前提)
     * root          = 设备有 root (缺失权限时用 su 降级执行/自愈授权)
     */
    @Volatile
    private var rootAvailable: Boolean = false

    /** 本次连接是否已尝试过 root 自愈授权 (避免每条指令都刷 su) */
    @Volatile
    private var selfHealTriedForConnection: Boolean = false

    private const val ACCESSIBILITY_COMPONENT =
        "org.autojs.autojs6/org.autojs.autojs.core.accessibility.AccessibilityServiceUsher"

    /** 缺无障碍时回给 PC 端的统一操作指引 */
    private const val HINT_ACCESSIBILITY =
        "设备未开启无障碍服务，远程截图/按键/手势均无法执行。" +
            "请在设备上操作：系统设置 → 无障碍 → 开启 AutoJs6；" +
            "已连接 adb 时可执行：adb shell settings put secure enabled_accessibility_services " +
            ACCESSIBILITY_COMPONENT +
            " && adb shell settings put secure accessibility_enabled 1（重新运行 build-client.ps1 安装也会自动授权）"

    /** 缺存储权限时回给 PC 端的统一操作指引 */
    private const val HINT_STORAGE =
        "脚本无法写入设备存储：缺少「所有文件访问」权限。" +
            "请在设备上操作：系统设置 → 应用 → AutoJs6 → 权限 → 文件/媒体(所有文件访问)；" +
            "已连接 adb 时可执行：adb shell appops set org.autojs.autojs6 MANAGE_EXTERNAL_STORAGE allow"

    @Synchronized
    fun connectIfConfigured() {
        val address = MgmtPref.serverAddress.trim()
        val secret = MgmtPref.secret.trim()
        if (address.isEmpty() || secret.isEmpty() || !MgmtPref.sessionEnabled) {
            // 配置不完整, 或用户已退出登录 (凭证保留但不自动上线): 复位为安静的未连接态
            resetDisconnected()
            return
        }
        connect(address)
    }

    /**
     * 用户主动退出登录: 取消退避重连/心跳, 关闭 WebSocket, 清空鉴权展示信息并翻转 UI。
     *
     * 调用方 (Mgmt 门面) 必须**先**把 MgmtPref.sessionEnabled 置为 false (地址/秘钥可保留
     * 供登录弹窗回填)、再停止前台服务: close 触发的 onClosed 会走 scheduleReconnect,
     * 开关为 false 使其立即跳过; 否则 START_STICKY 服务下次被系统重建时会再次上线。
     */
    @Synchronized
    fun logout() {
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        stopHeartbeat()
        connectionActive = false
        authRejected = false
        lastConnectKey = null
        tenantId = null
        tenantName = null
        // 清空 outbox: 积压消息不得跨账号/跨租户重放（防越权数据泄漏, 方案 §5.3）
        OutboxManager.clear()
        val ws = webSocket
        webSocket = null
        runCatching { ws?.close(1000, "user logout") }
        resetDisconnected()
        Log.i(TAG, "user logged out from management platform")
    }

    @Synchronized
    fun connect(address: String) {
        val connectKey = "$address|${MgmtPref.secret.trim()}"
        // 幂等: 同配置连接已存在(进行中或已建立)时跳过; 退避等待中也不允许旁路重连。
        // 只有配置(地址/接入码)变化才会重建连接——这也让"改秘钥立即重连"自然生效。
        if (connectKey == lastConnectKey && (connectionActive || reconnectFuture?.isDone == false)) {
            Log.d(TAG, "connect skipped: same connection already active/pending")
            return
        }
        lastConnectKey = connectKey

        // 连接前取消已有的重连任务，避免并发创建多个 WebSocket
        reconnectFuture?.cancel(false)
        reconnectFuture = null

        val url = buildWebSocketUrl(address)
        val request = Request.Builder().url(url).build()
        // 新连接建立期间一律视为 RECOVERING, 直到收到 AUTH_OK; 此前是否已处于升级提示
        // 决定提示窗口行为 (安静重走 / 保持提示不闪烁), 升级链在此统一武装。
        authRejected = false
        enterRecovering(alreadyEscalated = status.escalated)
        tenantId = null
        tenantName = null
        selfHealTriedForConnection = false
        webSocket?.cancel()
        connectionActive = true
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                // 身份守卫: 旧 socket 被 cancel 后的迟到回调 (在大量媒体帧占用分发线程时
                // 可能晚于新连接事件到达) 不得触碰当前连接, 否则会把新连接的
                // connectionActive/心跳/CONNECTED 状态全部冲掉并再建一条 socket。
                if (ws !== webSocket) return
                Log.d(TAG, "onOpen")
                reconnectAttempts = 0
                sendDeviceInfo()
                sendScriptListSafe()
                sendInstalledAppsSafe()
                startHeartbeat()
                // root 设备 (含常见模拟器): 后台探测 root 并尝试静默补齐无障碍/存储授权,
                // 成功后系统绑定无障碍服务, onServiceConnected 钩子会主动回推 CAPABILITIES。
                rootExecutor.execute {
                    runCatching {
                        rootAvailable = RootUtils.isRootAvailable()
                        trySelfHealPermissions()
                    }
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (ws !== webSocket) return
                Log.d(TAG, "onClosed: $code $reason")
                connectionActive = false
                stopHeartbeat()
                onConnectionLost()
                // 4001 = 接入码无效, 慢速重试 (用户可能正在修改秘钥, 改完会主动触发 connect)
                scheduleReconnect(code == 4001 || authRejected)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (ws !== webSocket) return
                Log.w(TAG, "onFailure", t)
                connectionActive = false
                stopHeartbeat()
                onConnectionLost()
                scheduleReconnect(response?.code == 4001 || authRejected)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (ws !== webSocket) return
                handleServerMessage(text)
            }
        })
    }

    private fun buildWebSocketUrl(address: String): String {
        val trimmed = address.trim()
        val deviceId = deviceId()
        val secret = MgmtPref.secret.trim()

        fun appendQuery(base: String): String {
            val encodedDeviceId = URLEncoder.encode(deviceId, "UTF-8")
            val encodedSecret = URLEncoder.encode(secret, "UTF-8")
            val sep = if (base.contains("?")) "&" else "?"
            val withDevice = base + sep + "deviceId=" + encodedDeviceId
            return if (secret.isNotEmpty()) "$withDevice&matchCode=$encodedSecret" else withDevice
        }

        var base = trimmed
        if (!base.startsWith("ws://") && !base.startsWith("wss://") &&
            !base.startsWith("http://") && !base.startsWith("https://")) {
            base = "ws://$base"
        }

        if (base.startsWith("http://")) base = base.replaceFirst("http://", "ws://")
        if (base.startsWith("https://")) base = base.replaceFirst("https://", "wss://")

        val schemeEnd = base.indexOf("://") + 3
        val pathStart = base.indexOf('/', schemeEnd)

        if (pathStart == -1) {
            base += "/ws/device"
        } else if (pathStart == base.length - 1) {
            base += "ws/device"
        }

        return appendQuery(base)
    }

    private fun sendDeviceInfo() {
        val payload = JSONObject()
        val id = deviceId()
        payload.put("deviceId", id)
        payload.put("model", Build.MODEL)
        payload.put("androidVersion", Build.VERSION.RELEASE)
        payload.put("appVersion", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        payload.put("manufacturer", Build.MANUFACTURER)

        // Add status fields
        collectDeviceStatus(payload)
        payload.put("capabilities", collectCapabilities())

        val extra = JSONObject()
        extra.put("brand", Build.BRAND)
        extra.put("sdkInt", Build.VERSION.SDK_INT)
        val secret = MgmtPref.secret.trim()
        if (secret.isNotEmpty()) {
            extra.put("matchCode", secret)
        }
        payload.put("extra", extra)

        send("DEVICE_INFO", payload)
    }

    private fun startHeartbeat() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = scheduler.scheduleAtFixedRate(
            // scheduleAtFixedRate 任务体一旦抛出未捕获异常, 后续周期会被静默永久取消
            // (心跳静默停摆, 设备将被服务端超时判死且无任何日志); 单次失败必须吞掉。
            { runCatching { sendHeartbeat() }.onFailure { Log.w(TAG, "heartbeat failed", it) } },
            5L, // initial delay in seconds
            15L, // heartbeat interval in seconds
            TimeUnit.SECONDS,
        )
    }

    private fun stopHeartbeat() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
    }

    private fun sendHeartbeat() {
        val payload = JSONObject()
        payload.put("timestamp", System.currentTimeMillis())

        collectDeviceStatus(payload)
        payload.put("capabilities", collectCapabilities())

        Log.d(TAG, "Sending Heartbeat: $payload")
        send("HEARTBEAT", payload)
    }

    /**
     * 收集设备能力快照。无障碍/存储状态实时读取, root 状态使用 onOpen 时缓存的探测结果
     * (su 探测较重, 不放进每条心跳)。
     */
    private fun collectCapabilities(): JSONObject {
        val caps = JSONObject()
        caps.put("accessibility", AutoJsAccessibilityService.instance != null)
        caps.put(
            "storage",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            },
        )
        caps.put("root", rootAvailable)
        return caps
    }

    /**
     * 主动推送能力变化 (无障碍服务连接/断开时由 AccessibilityService 回调触发)。
     */
    fun onAccessibilityStateChanged() {
        scheduler.execute {
            Log.d(TAG, "accessibility state changed, push CAPABILITIES")
            send("CAPABILITIES", collectCapabilities())
        }
        // 服务可能是被系统回收/用户手动关闭: 已鉴权连接上再试一次 root 自愈 (独立线程, 不阻塞心跳)
        rootExecutor.execute {
            runCatching { trySelfHealPermissions() }
        }
    }

    /**
     * 指令结果回报: PC 端下发的指令携带 commandId, 设备执行失败时回传失败原因与操作指引,
     * 服务端据此把 HTTP 请求变为业务错误, 前端立刻弹出提示 (而不是"点了没反应")。
     */
    private fun replyCommandResult(commandId: String?, success: Boolean, message: String? = null) {
        val cid = commandId?.takeIf { it.isNotEmpty() } ?: return
        val payload = JSONObject()
        payload.put("commandId", cid)
        payload.put("success", success)
        if (!message.isNullOrEmpty()) {
            payload.put("message", message)
        }
        send("COMMAND_RESULT", payload)
    }

    /**
     * root 设备自愈授权: 静默开启本应用无障碍服务 + 授予所有文件访问 + 建脚本目录。
     * 仅在探测到 root 时动作, 每次连接最多一次; 失败不影响任何正常流程。
     */
    private fun trySelfHealPermissions() {
        if (selfHealTriedForConnection) return
        selfHealTriedForConnection = true
        if (!rootAvailable) return

        val needsA11y = AutoJsAccessibilityService.instance == null
        val needsStorage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()
        if (needsA11y) {
            // 与 build-client.ps1 的 adb 授权等价, 只是改成设备侧 su 执行, 重装后无需人工设置
            val ok1 = execRoot(
                "settings put secure enabled_accessibility_services $ACCESSIBILITY_COMPONENT",
            )
            val ok2 = execRoot("settings put secure accessibility_enabled 1")
            Log.i(TAG, "self-heal accessibility via root: settings=$ok1/$ok2")
        }
        if (needsStorage) {
            val ok = execRoot("appops set org.autojs.autojs6 MANAGE_EXTERNAL_STORAGE allow")
            Log.i(TAG, "self-heal storage via root: appops=$ok")
        }
        // 脚本工作目录缺失时 PUSH_SCRIPT 会 ENOENT, root 下直接兜底创建
        execRoot("mkdir -p ${WorkingDirectoryUtils.path}/management_pushed")
        scheduler.schedule(
            { runCatching { send("CAPABILITIES", collectCapabilities()) } },
            3L,
            TimeUnit.SECONDS,
        )
    }

    /** 以 root 执行单条 shell, 返回是否成功 (exit 0)。超时 10s 强杀, 防卡死。 */
    private fun execRoot(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val future = rootExecutor.submit(Callable { process.waitFor() })
            try {
                future.get(10, TimeUnit.SECONDS) == 0
            } catch (e: Exception) {
                future.cancel(true)
                runCatching { process.destroy() }
                Log.w(TAG, "execRoot timeout: $command")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "execRoot failed: $command", e)
            false
        }
    }

    private fun collectDeviceStatus(payload: JSONObject) {
        try {
            // Battery
            val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level != -1 && scale > 0) {
                    payload.put("battery", (level * 100 / scale.toFloat()).toInt())
                }
                val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                payload.put("isCharging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            }

            // Volume (Music stream)
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                // payload.put("maxVolume", audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                payload.put("volume", currentVolume)
            }

            // Brightness
            try {
                val brightness = Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                // Brightness is usually 0-255
                payload.put("brightness", (brightness * 100 / 255.0).toInt())
            } catch (e: Exception) {
                // Ignore
            }

            // Bluetooth
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter != null) {
                    payload.put("bluetoothEnabled", adapter.isEnabled)
                }
            } catch (e: Exception) {
                // Ignore
            }

        } catch (e: Exception) {
            Log.w(TAG, "Failed to collect device status", e)
        }
    }

    /**
     * 断线重连 (单飞 + 指数退避):
     * - 同一时刻只有一个待执行的重连任务;
     * - 普通网络故障: 2s 起翻倍, 上限 60s, 加 ±25% 抖动, 成功后计数归零;
     * - 鉴权失败 4001: 固定 60s, 避免错误接入码每几秒刷一次服务端;
     * - 每次重试都重新读 Pref, 用户改完地址/秘钥后下一次重连即生效。
     */
    @Synchronized
    private fun scheduleReconnect(authFailed: Boolean = false) {
        val address = MgmtPref.serverAddress.trim()
        val secret = MgmtPref.secret.trim()
        if (address.isEmpty() || secret.isEmpty() || !MgmtPref.sessionEnabled) {
            return
        }
        reconnectFuture?.cancel(false)

        val baseDelay = if (authFailed) {
            AUTH_FAIL_RETRY_SECONDS
        } else {
            val exp = minOf(reconnectAttempts, 5)
            minOf(MAX_BACKOFF_SECONDS, 2L shl exp)
        }
        val jitter = (Math.random() * 0.5 + 0.75) // 0.75x ~ 1.25x
        val delaySeconds = (baseDelay * jitter).toLong().coerceAtLeast(1)
        reconnectAttempts++

        Log.d(TAG, "将在 ${delaySeconds}s 后重连 (attempt=$reconnectAttempts, authFailed=$authFailed)")
        reconnectFuture = scheduler.schedule(
            {
                try {
                    // 必须先清空自身引用: connect() 的幂等守卫会把"未完成的 future"视为
                    // 已有待执行连接, 不清空会把正在运行的重连任务自己挡掉。
                    reconnectFuture = null
                    connectIfConfigured()
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect failed", e)
                }
            },
            delaySeconds,
            TimeUnit.SECONDS,
        )
    }

    fun send(type: String, payload: JSONObject) {
        // 终态/回执/日志类消息先持久化进 outbox（信封顶层带 msgId, 方案 §5.3/§6.1）:
        // 即使随后 ws 不可用或发送后 ACK 丢失, 下次 AUTH_OK 会按序重放, 服务端幂等去重;
        // 非关键消息（心跳/全量同步/截图文本等）维持原语义, 不排队。
        val text = if (OutboxManager.isDurable(type)) {
            OutboxManager.enqueue(type, payload)
        } else {
            JSONObject().put("type", type).put("payload", payload).toString()
        }
        val ws = webSocket
        if (ws == null) {
            // 未连接: 关键消息已在 outbox 等待重放, 非关键消息按旧行为直接丢弃。
            return
        }
        runCatching { ws.send(text) }.onFailure {
            Log.w(TAG, "ws send failed ($type); durable message waits for replay", it)
        }
    }

     /**
     * Lightweight connection test used from settings.
     *
     * 注意: WebSocket 握手 (HTTP 101) 成功只代表 TCP/升级通, 不代表 matchCode 鉴权通过 ——
     * 服务端在升级完成后才异步校验接入码, 失败时回 AUTH_FAILED 并以 4001 关闭。
     * 因此这里必须以收到 AUTH_OK 作为成功判据, onOpen 不能直接回调成功。
     */
    fun testConnection(callback: (Boolean, String?) -> Unit) {
        val address = MgmtPref.serverAddress.trim()
        val secret = MgmtPref.secret.trim()
        if (address.isEmpty()) {
            callback(false, "Server address is empty")
            return
        }
        if (secret.isEmpty()) {
            callback(false, "Secret is empty")
            return
        }

        val baseUrl = buildWebSocketUrl(address)
        // 测试连接使用 mode=test，不参与设备在线状态与心跳逻辑
        val testUrl = if (baseUrl.contains("?")) "$baseUrl&mode=test" else "$baseUrl?mode=test"
        val request = Request.Builder().url(testUrl).build()

        // 兜底超时, 防止服务端无任何响应时弹窗卡死
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        // 用数组持有 socket 供超时任务访问 (WebSocket 创建与回调注册存在先后)
        val holder = arrayOfNulls<WebSocket>(1)

        val timeoutFuture = scheduler.schedule({
            if (done.compareAndSet(false, true)) {
                runCatching { holder[0]?.cancel() }
                callback(false, "timeout waiting for auth result")
            }
        }, 5, java.util.concurrent.TimeUnit.SECONDS)

        fun finish(ok: Boolean, error: String?) {
            if (!done.compareAndSet(false, true)) return
            timeoutFuture.cancel(false)
            callback(ok, error)
            runCatching { if (ok) holder[0]?.close(1000, "test finished") else holder[0]?.cancel() }
        }

        val socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                // 故意不判定成功: 等待服务端 AUTH_OK / AUTH_FAILED
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    when (obj.optString("type")) {
                        "AUTH_OK" -> finish(true, null)
                        "AUTH_FAILED" -> finish(
                            false,
                            obj.optJSONObject("payload")?.optString("message")
                                ?.takeIf { it.isNotEmpty() } ?: "Auth failed"
                        )
                    }
                } catch (e: Exception) {
                    finish(false, "Bad response: ${e.message}")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                finish(false, "closed code=$code reason=$reason")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                finish(false, t.message ?: "connection failed")
            }
        })
        holder[0] = socket
    }

    private fun handleServerMessage(text: String) {
        try {
            val obj = JSONObject(text)
            val type = obj.optString("type")
            val payloadAny = obj.opt("payload")
            val payload = when (payloadAny) {
                is JSONObject -> payloadAny
                else -> JSONObject()
            }
            when (type) {
                "AUTH_OK" -> {
                    // 正式连接鉴权通过: 此时才是真正"已连接到平台"
                    Log.d(TAG, "AUTH_OK, connected to platform")
                    tenantId = payload.optString("tenantId").takeIf { it.isNotEmpty() }
                    tenantName = payload.optString("tenantName").takeIf { it.isNotEmpty() }
                    onAuthenticated()
                }
                "MESSAGE_ACK" -> {
                    // 上行消息服务端处理确认（方案 §6.1）: 从 outbox 删除对应条目。
                    // duplicate=true 表示重放帧被去重, 同样代表服务端已处理过, 正常清理。
                    OutboxManager.acknowledge(payload.optString("msgId"))
                }
                "AUTH_FAILED" -> {
                    Log.w(TAG, "AUTH_FAILED: ${payload.optString("message")}")
                    authRejected = true
                    // 已处于升级提示态时立即刷新为"秘钥无效"原因; 安静窗口内则
                    // 由 30s 升级任务经 currentIssue() 自动带出, 不打扰短抖动场景。
                    if (status.escalated && status.issue != ConnectionIssue.AUTH_REJECTED) {
                        publishStatus(status.copy(issue = ConnectionIssue.AUTH_REJECTED))
                    }
                }
                "REQUEST_SCRIPT_LIST" -> sendScriptListSafe()
                "PUSH_SCRIPT" -> handlePushScript(payload)
                "RUN_SCRIPT" -> handleRunScript(payload)
                "REQUEST_SCREENSHOT" -> handleRequestScreenshot(payload)
                "TOUCH_EVENT" -> handleTouchEvent(payload)
                "DEVICE_ACTION" -> handleDeviceAction(payload)
                "REQUEST_RUNNING_SCRIPTS" -> sendRunningScriptsSafe()
                "REQUEST_SCHEDULED_SCRIPTS" -> sendScheduledScriptsSafe()
                "REQUEST_INSTALLED_APPS" -> sendInstalledAppsSafe()
                "REQUEST_LOG_TAIL" -> handleRequestLogTail(payload)
                "REQUEST_SCRIPT_CONTENT" -> handleRequestScriptContent(payload)
                "UPDATE_SCRIPT_CONTENT" -> handleUpdateScriptContent(payload)
                "DELETE_SCRIPT" -> handleDeleteScript(payload)
                "CREATE_FOLDER" -> handleCreateFolder(payload)
                "CREATE_INTENT_TASK" -> handleCreateIntentTask(payload)
                "CREATE_TIMED_TASK" -> handleCreateTimedTask(payload)
                "DELETE_SCHEDULED_TASK" -> handleDeleteScheduledTask(payload)
                "INSTALL_APK" -> handleInstallApk(payload)
                else -> Log.w(TAG, "Unknown server message type: $type")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle server message", e)
        }
    }

    private fun buildHttpBaseUrl(address: String): String {
        val trimmed = address.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("ws://") -> trimmed.replaceFirst("ws://", "http://")
            trimmed.startsWith("wss://") -> trimmed.replaceFirst("wss://", "https://")
            else -> "http://$trimmed"
        }
    }

    private fun scriptsRootDir(): File = File(WorkingDirectoryUtils.path)

    private data class ScriptSummary(
        val id: String,
        val name: String,
        val path: String,
        val size: Long,
        val updatedAt: Long,
    )

    private fun collectScriptsAndFolders(): Pair<List<ScriptSummary>, List<String>> {
        val root = scriptsRootDir()
        val scripts = mutableListOf<ScriptSummary>()
        val folders = mutableListOf<String>()

        fun scan(dir: File) {
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.isDirectory) {
                    val relPath = try {
                        root.toPath().relativize(f.toPath()).toString().replace('\\', '/')
                    } catch (_: Exception) {
                        f.name
                    }
                    folders.add(relPath)
                    scan(f)
                } else {
                    val scriptFile = ScriptFile(f)
                    val type = scriptFile.type
                    if (type == ScriptFile.TYPE_JAVASCRIPT || type == ScriptFile.TYPE_AUTO) {
                        val id = f.absolutePath
                        val name = scriptFile.name
                        val relPath = try {
                            root.toPath().relativize(f.toPath()).toString().replace('\\', '/')
                        } catch (_: Exception) {
                            f.name
                        }
                        scripts.add(
                            ScriptSummary(
                                id = id,
                                name = name,
                                path = relPath,
                                size = f.length(),
                                updatedAt = f.lastModified(),
                            ),
                        )
                    }
                }
            }
        }
        if (root.exists() && root.isDirectory) {
            scan(root)
        }
        return Pair(scripts, folders)
    }

    private fun sendScriptListSafe() {
        try {
            val (scripts, folders) = collectScriptsAndFolders()
            val payload = JSONObject()
            payload.put("deviceId", deviceId())
            
            val scriptArr = JSONArray()
            for (s in scripts) {
                val o = JSONObject()
                o.put("id", s.id)
                o.put("name", s.name)
                o.put("path", s.path)
                o.put("size", s.size)
                o.put("updatedAt", s.updatedAt)
                scriptArr.put(o)
            }
            payload.put("scripts", scriptArr)

            val folderArr = JSONArray()
            for (f in folders) {
                folderArr.put(f)
            }
            payload.put("folders", folderArr)

            send("SCRIPT_LIST", payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send script list", e)
        }
    }

    private fun sendInstalledAppsSafe() {
        try {
            val context = appContext
            val pm = context.packageManager
            val apps = AppUtils.getInstalledApplications(context)
            val arr = JSONArray()
            for (app in apps) {
                val obj = JSONObject()
                val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                obj.put("packageName", app.packageName)
                obj.put("name", label)

                val versionInfo = AppUtils.getInstalledVersionInfo(app.packageName)
                if (versionInfo != null) {
                    obj.put("versionName", versionInfo.versionName)
                    obj.put("versionCode", versionInfo.versionCode)
                }

                obj.put("targetSdk", app.targetSdkVersion)
                val isSystem =
                    (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                obj.put("isSystem", isSystem)

                arr.put(obj)
            }

            val payload = JSONObject()
            payload.put("deviceId", deviceId())
            payload.put("apps", arr)
            send("INSTALLED_APPS", payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send installed apps", e)
        }
    }

    private fun handleCreateFolder(payload: JSONObject) {
        val folder = payload.optString("folder")
        if (folder.isEmpty()) return
        try {
            val root = scriptsRootDir()
            val target = File(root, folder)
            if (!target.exists()) {
                target.mkdirs()
            }
            sendScriptListSafe()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle CREATE_FOLDER", e)
        }
    }

    private fun handlePushScript(payload: JSONObject) {
        val commandId = payload.optString("commandId")
        val name = payload.optString("name").ifEmpty { payload.optString("scriptId", "script.js") }
        val content = payload.optString("content", "")
        val runImmediately = payload.optBoolean("runImmediately", false)
        val targetFolderName = payload.optString("targetFolder", "management_pushed")

        if (content.isEmpty()) {
            replyCommandResult(commandId, false, "脚本内容为空，已拒绝执行")
            return
        }
        try {
            val root = scriptsRootDir()
            val targetDir = if (targetFolderName == "." || targetFolderName.isEmpty()) {
                root
            } else {
                File(root, targetFolderName)
            }
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                Log.w(TAG, "PUSH_SCRIPT target mkdirs returned false: $targetDir")
            }
            val path = File(targetDir, name).absolutePath
            PFiles.createWithDirs(path)
            PFiles.write(path, content)

            sendScriptListSafe()
            replyCommandResult(commandId, true)

            if (runImmediately) {
                runScriptInternal(path)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle PUSH_SCRIPT", e)
            replyCommandResult(commandId, false, "$HINT_STORAGE（底层错误：${e.message ?: e.javaClass.simpleName}）")
        }
    }

    private fun handleRunScript(payload: JSONObject) {
        val commandId = payload.optString("commandId")
        val scriptId = payload.optString("scriptId")
        if (scriptId.isEmpty()) {
            replyCommandResult(commandId, false, "缺少 scriptId，无法执行脚本")
            return
        }
        if (!File(scriptId).exists()) {
            replyCommandResult(commandId, false, "设备上不存在脚本文件：$scriptId")
            return
        }
        replyCommandResult(commandId, true)
        runScriptInternal(scriptId)
    }

    private fun runScriptInternal(path: String) {
        try {
            val context = appContext
            val file = ScriptFile(path)
            org.autojs.autojs.model.script.Scripts.run(context, file)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to run script: $path", e)
        }
    }

    private fun sendRunningScriptsSafe() {
        try {
            val autoJs = AutoJs.instance
            val executions = autoJs.scriptEngineService.scriptExecutions
            val arr = JSONArray()
            for (execution in executions) {
                val source = execution.source
                val (id, name) = when (source) {
                    is ScriptSource -> source.fullPath to source.name
                    else -> source.toString() to source.toString()
                }
                val obj = JSONObject()
                obj.put("id", id)
                obj.put("name", name)
                arr.put(obj)
            }

            val payload = JSONObject()
            payload.put("deviceId", deviceId())
            payload.put("items", arr)
            send("RUNNING_SCRIPTS", payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send running scripts", e)
        }
    }

    private fun sendScheduledScriptsSafe() {
        try {
            val arr = JSONArray()
            val tasks: List<TimedTask> = TimedTaskManager.allTasksAsList

            for (task in tasks) {
                val obj = JSONObject()

                val scriptPath = task.scriptPath
                val id = if (!scriptPath.isNullOrEmpty()) scriptPath else task.id.toString()
                val scheduleId = "timed:${task.id}"
                val name = if (!scriptPath.isNullOrEmpty()) File(scriptPath).name else "定时任务 #${task.id}"

                obj.put("id", id)
                obj.put("name", name)
                obj.put("scheduleId", scheduleId)
                obj.put("type", "timed")

                val cron = buildCronDescription(task)
                if (cron.isNotEmpty()) {
                    obj.put("cron", cron)
                }

                val next = try {
                    task.nextTime
                } catch (e: Exception) {
                    -1L
                }
                if (next > 0) {
                    obj.put("nextRunAt", next)
                }

                arr.put(obj)
            }

            // 广播触发任务（IntentTask）
            val intentTasks = TimedTaskManager.allIntentTasksAsList
            for (task in intentTasks) {
                val obj = JSONObject()

                val scriptPath = task.scriptPath
                val id = if (!scriptPath.isNullOrEmpty()) scriptPath else "intent:${task.id}"
                val scheduleId = "intent:${task.id}"
                val name = if (!scriptPath.isNullOrEmpty()) File(scriptPath).name else "广播任务 #${task.id}"

                obj.put("id", id)
                obj.put("name", name)
                obj.put("scheduleId", scheduleId)
                obj.put("type", "intent")

                val desc = mutableListOf<String>()
                val action = task.action
                if (!action.isNullOrEmpty()) {
                    desc += action
                }
                val category = task.category
                if (!category.isNullOrEmpty()) {
                    desc += "category=$category"
                }
                val dataType = task.dataType
                if (!dataType.isNullOrEmpty()) {
                    desc += "type=$dataType"
                }
                if (desc.isNotEmpty()) {
                    obj.put("cron", desc.joinToString(" · "))
                }

                arr.put(obj)
            }

            val payload = JSONObject()
            payload.put("deviceId", deviceId())
            payload.put("items", arr)
            send("SCHEDULED_SCRIPTS", payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send scheduled scripts", e)
        }
    }

    private fun buildCronDescription(task: TimedTask): String {
        return try {
            if (task.isDisposable) {
                val dt = DateTime(task.millis)
                "一次 ${dt.toString("yyyy-MM-dd HH:mm")}" // 一次性任务
            } else if (task.isDaily) {
                val time = LocalTime.fromMillisOfDay(task.millis)
                "每天 ${time.toString("HH:mm")}" // 每天固定时间
            } else {
                val time = LocalTime.fromMillisOfDay(task.millis)
                val timeStr = time.toString("HH:mm")
                val ctx = GlobalAppContext.get()
                val days = mutableListOf<String>()
                if (task.hasDayOfWeek(ctx, DateTimeConstants.MONDAY)) days += "周一"
                if (task.hasDayOfWeek(ctx, DateTimeConstants.TUESDAY)) days += "周二"
                if (task.hasDayOfWeek(ctx, DateTimeConstants.WEDNESDAY)) days += "周三"
                if (task.hasDayOfWeek(ctx, DateTimeConstants.THURSDAY)) days += "周四"
                if (task.hasDayOfWeek(ctx, DateTimeConstants.FRIDAY)) days += "周五"
                if (task.hasDayOfWeek(ctx, DateTimeConstants.SATURDAY)) days += "周六"
                if (task.hasDayOfWeek(ctx, DateTimeConstants.SUNDAY)) days += "周日"

                if (days.isEmpty()) {
                    ""
                } else {
                    "每周${days.joinToString("、")} $timeStr"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build cron description for timed task", e)
            ""
        }
    }

    private fun handleDeleteScheduledTask(payload: JSONObject) {
        val id = payload.optString("id")
        if (id.isEmpty()) return

        try {
            when {
                id.startsWith("timed:") -> {
                    val taskId = id.removePrefix("timed:").toLongOrNull() ?: return
                    val task = TimedTaskManager.getTimedTask(taskId) ?: return
                    TimedTaskManager.removeTaskSync(task)
                }

                id.startsWith("intent:") -> {
                    val taskId = id.removePrefix("intent:").toLongOrNull() ?: return
                    val task = TimedTaskManager.getIntentTask(taskId) ?: return
                    TimedTaskManager.removeTaskSync(task)
                }

                else -> {
                    // 兼容旧格式：可能直接用脚本路径作为 id
                    val tasks = TimedTaskManager.allTasksAsList
                    val match = tasks.firstOrNull { it.scriptPath == id }
                    if (match != null) {
                        TimedTaskManager.removeTaskSync(match)
                    }
                }
            }

            // 删除定时任务后，刷新一次定时任务列表
            sendScheduledScriptsSafe()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle DELETE_SCHEDULED_TASK: $id", e)
        }
    }

    private fun handleInstallApk(payload: JSONObject) {
        val apkName = payload.optString("apkName").trim()
        if (apkName.isEmpty()) {
            Log.w(TAG, "INSTALL_APK missing apkName")
            return
        }

        val mode = payload.optString("mode", "auto")

        // 下载与安装可能持续数分钟, 必须在专用 installExecutor 执行, 严禁占用 scheduler
        // (单线程, 承载心跳/退避重连/状态升级定时任务)。
        installExecutor.execute {
            try {
                val address = MgmtPref.serverAddress.trim()
                if (address.isEmpty()) {
                    Log.w(TAG, "INSTALL_APK ignored: empty server address in Pref")
                    return@execute
                }

                val base = buildHttpBaseUrl(address).trimEnd('/')
                val encodedName = URLEncoder.encode(apkName, "UTF-8")
                // 设备端无登录态, 用租户接入码 (api_key) 作为下载鉴权,
                // 服务端按码解析租户后只在其租户空间内查找文件。
                val encodedCode = URLEncoder.encode(MgmtPref.secret.trim(), "UTF-8")
                val url = "$base/api/apk/files/$encodedName?matchCode=$encodedCode"

                val request = Request.Builder().url(url).build()
                Log.d(TAG, "Downloading APK from $url")
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "INSTALL_APK download failed: HTTP ${'$'}{response.code}")
                    response.close()
                    return@execute
                }

                val body = response.body
                if (body == null) {
                    Log.w(TAG, "INSTALL_APK download failed: empty body")
                    response.close()
                    return@execute
                }

                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                    Log.w(TAG, "INSTALL_APK cannot create downloads dir: ${'$'}downloadsDir")
                    response.close()
                    return@execute
                }

                val targetFile = File(downloadsDir, apkName)
                body.byteStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                response.close()

                Log.d(TAG, "APK downloaded to ${'$'}targetFile")

                val wantRoot = when (mode) {
                    "root" -> true
                    "normal" -> false
                    else -> true // auto
                }
                val hasRoot = RootUtils.isRootAvailable()
                val useRoot = wantRoot && hasRoot

                if (useRoot) {
                    val ok = installApkWithRoot(targetFile)
                    if (ok) {
                        Log.d(TAG, "INSTALL_APK installed via root")
                        return@execute
                    }
                    Log.w(TAG, "INSTALL_APK root install failed, fallback to normal installer")
                }

                IntentUtils.installApk(
                    appContext,
                    targetFile.absolutePath,
                    AppFileProvider.AUTHORITY,
                    ToastExceptionHolder(appContext),
                )
                Log.d(TAG, "INSTALL_APK launched system installer for ${'$'}targetFile")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to handle INSTALL_APK", e)
            }
        }
    }

    private fun installApkWithRoot(file: File): Boolean {
        return try {
            val cmd = "pm install -r \"${'$'}{file.absolutePath}\""
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            // waitFor 本身无超时, 与 execRoot 同样放到 rootExecutor 并加 120s 上限,
            // 防止 root 授权弹窗无人响应/pm 卡死时长时间占住安装线程。
            val future = rootExecutor.submit(Callable { process.waitFor() })
            try {
                val code = future.get(120, TimeUnit.SECONDS)
                Log.d(TAG, "pm install exit code=$code")
                code == 0
            } catch (e: Exception) {
                future.cancel(true)
                runCatching { process.destroy() }
                Log.w(TAG, "installApkWithRoot timeout after 120s: ${file.name}")
                false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "installApkWithRoot failed", e)
            false
        }
    }

    private fun handleCreateIntentTask(payload: JSONObject) {
        val scriptId = payload.optString("scriptId")
        val action = payload.optString("action")
        if (scriptId.isEmpty() || action.isEmpty()) return

        try {
            val hasLocal = payload.has("local")
            val localFlag = if (hasLocal) {
                payload.optBoolean("local", false)
            } else {
                action == DynamicBroadcastReceivers.ACTION_STARTUP
            }

            val task = IntentTask().apply {
                scriptPath = scriptId
                this.action = action
                isLocal = localFlag
            }

            TimedTaskManager.addTaskSync(task)

            // 创建广播任务后，刷新一次定时/广播任务列表
            sendScheduledScriptsSafe()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle CREATE_INTENT_TASK for script: $scriptId, action: $action", e)
        }
    }

    private fun handleCreateTimedTask(payload: JSONObject) {
        val scriptId = payload.optString("scriptId")
        if (scriptId.isEmpty()) return

        val mode = payload.optString("mode", "once")
        val config = ExecutionConfig.default

        try {
            val task: TimedTask? = when (mode) {
                "once" -> {
                    val ts = payload.optLong("timestamp", -1L)
                    if (ts <= 0L) {
                        Log.w(TAG, "CREATE_TIMED_TASK once mode requires valid timestamp")
                        null
                    } else {
                        val dt = DateTime(ts).toLocalDateTime()
                        TimedTask.disposableTask(dt, scriptId, config)
                    }
                }

                "daily" -> {
                    val timeStr = payload.optString("timeOfDay", "")
                    if (timeStr.isEmpty()) {
                        Log.w(TAG, "CREATE_TIMED_TASK daily mode requires timeOfDay")
                        null
                    } else {
                        val time = LocalTime.parse(timeStr) // 期望 HH:mm
                        TimedTask.dailyTask(time, scriptId, config)
                    }
                }

                "weekly" -> {
                    val timeStr = payload.optString("timeOfDay", "")
                    if (timeStr.isEmpty()) {
                        Log.w(TAG, "CREATE_TIMED_TASK weekly mode requires timeOfDay")
                        null
                    } else {
                        val daysArr = payload.optJSONArray("daysOfWeek")
                        if (daysArr == null || daysArr.length() == 0) {
                            Log.w(TAG, "CREATE_TIMED_TASK weekly mode requires daysOfWeek")
                            null
                        } else {
                            val time = LocalTime.parse(timeStr)
                            var flag = 0L
                            for (i in 0 until daysArr.length()) {
                                val dow = daysArr.optInt(i, -1)
                                if (dow in 1..7) {
                                    flag = flag or TimedTask.getDayOfWeekTimeFlag(dow)
                                }
                            }
                            if (flag == 0L) {
                                Log.w(TAG, "CREATE_TIMED_TASK weekly mode produced empty timeFlag")
                                null
                            } else {
                                TimedTask.weeklyTask(time, flag, scriptId, config)
                            }
                        }
                    }
                }

                else -> {
                    Log.w(TAG, "Unsupported CREATE_TIMED_TASK mode: $mode")
                    null
                }
            }

            if (task == null) {
                return
            }

            TimedTaskManager.addTaskSync(task)

            // 创建定时任务后，刷新一次定时任务列表
            sendScheduledScriptsSafe()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle CREATE_TIMED_TASK for script: $scriptId", e)
        }
    }

    private fun handleRequestLogTail(payload: JSONObject) {
        val scriptId = payload.optString("scriptId")
        if (scriptId.isEmpty()) return

        val requestedLines = payload.optInt("lines", DEFAULT_LOG_TAIL_LINES).let { hint ->
            if (hint <= 0) DEFAULT_LOG_TAIL_LINES else hint
        }

        try {
            val console = AutoJs.instance.globalConsole
            val allLines: List<String> = synchronized(console.logEntries) {
                console.logEntries.map { it.content.toString() }
            }

            val tailLines: List<String> = if (allLines.isEmpty()) {
                emptyList()
            } else if (scriptId == GLOBAL_LOG_ID) {
                // 全局总日志：直接从结尾截取
                allLines.takeLast(requestedLines.coerceAtMost(allLines.size))
            } else {
                val range = synchronized(scriptLogRanges) { scriptLogRanges[scriptId] }
                if (range == null) {
                    // 没有记录范围时，退化为全局日志尾部
                    allLines.takeLast(requestedLines.coerceAtMost(allLines.size))
                } else {
                    val start = range.startIndex.coerceIn(0, allLines.size)
                    val endExclusive = (range.endIndex ?: allLines.size).coerceIn(start, allLines.size)
                    val slice = allLines.subList(start, endExclusive)
                    if (slice.size <= requestedLines) slice else slice.takeLast(requestedLines)
                }
            }

            val arr = JSONArray()
            tailLines.forEach { arr.put(it) }

            val reply = JSONObject()
            reply.put("deviceId", deviceId())
            reply.put("scriptId", scriptId)
            reply.put("lines", arr)
            send("LOG_LINES", reply)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build log tail for $scriptId", e)
            val reply = JSONObject()
            reply.put("deviceId", deviceId())
            reply.put("scriptId", scriptId)
            reply.put("lines", JSONArray())
            send("LOG_LINES", reply)
        }
    }

    private fun handleRequestScriptContent(payload: JSONObject) {
        val scriptId = payload.optString("scriptId")
        if (scriptId.isEmpty()) return

        try {
            val file = File(scriptId)
            if (!file.exists() || !file.isFile) {
                Log.w(TAG, "Script file not found: $scriptId")
                return
            }
            val content = PFiles.read(file)
            val reply = JSONObject()
            reply.put("deviceId", deviceId())
            reply.put("scriptId", scriptId)
            reply.put("content", content)
            send("SCRIPT_CONTENT", reply)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read script content: $scriptId", e)
        }
    }

    private fun handleUpdateScriptContent(payload: JSONObject) {
        val scriptId = payload.optString("scriptId")
        if (scriptId.isEmpty()) return
        if (!payload.has("content")) return

        val content = payload.optString("content", null) ?: return

        try {
            PFiles.createWithDirs(scriptId)
            PFiles.write(scriptId, content)
            // 更新本地脚本后，刷新脚本列表（大小与更新时间会变化）
            sendScriptListSafe()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update script content: $scriptId", e)
        }
    }

    private fun handleDeleteScript(payload: JSONObject) {
        val scriptId = payload.optString("scriptId")
        if (scriptId.isEmpty()) return

        try {
            val file = File(scriptId)
            if (!file.exists() || !file.isFile) {
                Log.w(TAG, "Script file not found for delete: $scriptId")
                return
            }

            val root = scriptsRootDir()
            val filePath = file.canonicalPath
            val rootPath = root.canonicalPath
            if (!filePath.startsWith(rootPath)) {
                Log.w(TAG, "Refusing to delete script outside root: $scriptId")
                return
            }

            val deleted = file.delete()
            if (!deleted) {
                Log.w(TAG, "Failed to delete script file: $scriptId")
                return
            }

            sendScriptListSafe()
        } catch (e: Exception) {
            Log.w(TAG, "Exception while deleting script: $scriptId", e)
        }
    }

    private fun handleRequestScreenshot(payload: JSONObject) {
        val commandId = payload.optString("commandId").takeIf { it.isNotEmpty() }
        val quality = payload.optInt("quality", 75).coerceIn(30, 100)
        val maxWidth = payload.optInt("maxWidth", 0).coerceAtLeast(0)
        val maxHeight = payload.optInt("maxHeight", 0).coerceAtLeast(0)

        // 抓帧 in-flight 聚合：进行中再来的请求复用本次抓帧结果，避免并发系统截图把图形内存/线程打满
        synchronized(pendingScreenshotCommands) {
            if (screenshotInFlight) {
                commandId?.let { pendingScreenshotCommands.add(it) }
                Log.d(TAG, "REQUEST_SCREENSHOT coalesced while a capture is in-flight")
                return
            }
            screenshotInFlight = true
        }

        // 统一回执：本次发起者 + 聚合到的发起者回同一结果，并复位 in-flight（回执在锁外发送，避免持锁写网络）
        val finish: (Boolean, String?) -> Unit = { success, message ->
            val coalesced = synchronized(pendingScreenshotCommands) {
                screenshotInFlight = false
                val ids = pendingScreenshotCommands.toList()
                pendingScreenshotCommands.clear()
                ids
            }
            replyCommandResult(commandId, success, message)
            coalesced.forEach { replyCommandResult(it, success, message) }
        }

        val service = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AutoJsAccessibilityService.instance
        } else {
            null
        }
        if (service == null) {
            if (!rootAvailable) {
                Log.w(TAG, "REQUEST_SCREENSHOT ignored: accessibility service not running (no root)")
                finish(false, HINT_ACCESSIBILITY)
                return
            }
            // 无障碍截图不可用时, root 设备降级为 screencap (模拟器/root 真机由此免开无障碍也能看屏幕)。
            // screencap 阻塞, 必须放 root 线程, 不能卡住 WS 收消息线程。
            rootExecutor.execute {
                val rootOk = captureScreenshotViaRoot(quality, maxWidth, maxHeight)
                if (!rootOk) {
                    Log.w(TAG, "REQUEST_SCREENSHOT root fallback failed")
                }
                finish(rootOk, if (rootOk) null else HINT_ACCESSIBILITY)
            }
            return
        }

        // 抽成本地递归函数：系统限流 (errorCode=1) 时在截图线程上退避重试，避免连续抓帧直接失败
        fun dispatchAccessibilityScreenshot(attempt: Int) {
            try {
                // 关键：Executor 用截图专用后台线程，不再传 service.mainExecutor，避免连续抓帧卡死主线程
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    shotExecutor,
                    object : AndroidAccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AndroidAccessibilityService.ScreenshotResult) {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            try {
                                val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                                if (hardwareBitmap == null) {
                                    Log.w(TAG, "Screenshot hardwareBitmap is null")
                                    finish(false, "系统截图返回空数据，请稍后重试（或检查无障碍服务是否被系统限制）")
                                    return
                                }
                                val bitmap = try {
                                    hardwareBitmap.copy(Bitmap.Config.ARGB_8888, true)
                                } finally {
                                    hardwareBitmap.recycle()
                                }
                                if (bitmap == null) {
                                    finish(false, "截图内存不足，请稍后重试")
                                    return
                                }
                                val sent = sendScreenshotBitmap(bitmap, quality, maxWidth, maxHeight)
                                finish(sent, if (sent) null else "截图回传失败，请检查设备网络连接后重试")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to handle screenshot success", e)
                                finish(false, "截图处理失败：${e.message ?: e.javaClass.simpleName}")
                            } finally {
                                // HardwareBuffer 必须显式关闭，否则连续抓帧会泄漏图形内存（远程截图方案 A 关键修复）
                                runCatching { hardwareBuffer.close() }
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "Screenshot failed, errorCode=$errorCode, attempt=$attempt")
                            if (errorCode == AndroidAccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT &&
                                attempt < SCREENSHOT_MAX_ATTEMPTS - 1
                            ) {
                                // in-flight 继续保持，聚合到的请求随本次结果一起回执；线性退避 350/700ms
                                val delayMs = SCREENSHOT_RATE_LIMIT_RETRY_BASE_MS * (attempt + 1)
                                Log.d(TAG, "Screenshot rate-limited, retry in ${delayMs}ms (attempt=${attempt + 1})")
                                shotHandler.postDelayed({ dispatchAccessibilityScreenshot(attempt + 1) }, delayMs)
                                return
                            }
                            finish(false, "系统拒绝截图 (errorCode=$errorCode)。$HINT_ACCESSIBILITY")
                        }
                    },
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to request screenshot", e)
                finish(false, "截图调用异常：${e.message ?: e.javaClass.simpleName}。$HINT_ACCESSIBILITY")
            }
        }

        dispatchAccessibilityScreenshot(0)
    }

    /**
     * root 降级截图: `screencap -p <file>` 输出 PNG 到应用缓存目录,
     * 解码后复用无障碍截图的压缩/回传链路。成功回传返回 true。
     */
    private fun captureScreenshotViaRoot(
        quality: Int,
        maxWidth: Int,
        maxHeight: Int,
    ): Boolean {
        if (!rootAvailable) return false
        var tmp: File? = null
        return try {
            tmp = File(appContext.cacheDir, "mgmt_screenshot_${System.currentTimeMillis()}.png")
            val ok = execRoot("screencap -p ${tmp.absolutePath}")
            if (!ok || !tmp.exists() || tmp.length() == 0L) {
                Log.w(TAG, "root screencap failed: ok=$ok file=${tmp.exists()} size=${tmp.length()}")
                return false
            }
            val bitmap = BitmapFactory.decodeFile(tmp.absolutePath) ?: return false
            sendScreenshotBitmap(bitmap, quality, maxWidth, maxHeight)
        } catch (e: Exception) {
            Log.w(TAG, "root screencap exception", e)
            false
        } finally {
            tmp?.delete()
        }
    }

    /**
     * 发送二进制媒体帧（24 字节大端头 + 裸载荷，与服务端 binary-frame 协议对齐）。
     * 已成功入队 WebSocket 返回 true；无连接或异常返回 false。
     */
    private fun sendMediaBinaryFrame(
        msgType: Int,
        flags: Int,
        width: Int,
        height: Int,
        payload: ByteArray,
    ): Boolean {
        val ws = webSocket ?: return false
        return try {
            val header = ByteBuffer.allocate(MEDIA_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            header.put(MEDIA_BINARY_MAGIC.toByte())
            header.put(MEDIA_BINARY_VERSION.toByte())
            header.put(msgType.toByte())
            header.put(flags.toByte())
            header.putInt(nextMediaSequence())
            header.putLong(System.currentTimeMillis())
            header.putShort(width.toShort())
            header.putShort(height.toShort())
            header.putInt(payload.size)
            val frame = ByteArray(MEDIA_HEADER_SIZE + payload.size)
            System.arraycopy(header.array(), 0, frame, 0, MEDIA_HEADER_SIZE)
            System.arraycopy(payload, 0, frame, MEDIA_HEADER_SIZE, payload.size)
            // @mgmt-adapt okio 3.6 已将 ByteString.of(...) 标记废弃且 ByteArray.toByteString()
            // 扩展收为 internal, 统一走非废弃的 Buffer.write(byte[]).readByteString()。
            ws.send(okio.Buffer().write(frame).readByteString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send binary media frame", e)
            false
        }
    }

    /**
     * 压缩并回传一帧 JPEG。成功返回 true（失败原因由调用方汇总回执），并负责回收入参 bitmap；
     * 默认走二进制媒体帧，灰度开关关闭时回退旧文本 SCREENSHOT。
     */
    private fun sendScreenshotBitmap(
        bitmap: Bitmap,
        quality: Int,
        maxWidth: Int,
        maxHeight: Int,
    ): Boolean {
        var targetBitmap: Bitmap? = null
        return try {
            val screenWidth = bitmap.width
            val screenHeight = bitmap.height
            targetBitmap = if (maxWidth > 0 || maxHeight > 0) {
                val width = bitmap.width
                val height = bitmap.height
                var scale = 1.0f
                if (maxWidth > 0) {
                    val sx = maxWidth.toFloat() / width.toFloat()
                    if (sx < scale) {
                        scale = sx
                    }
                }
                if (maxHeight > 0) {
                    val sy = maxHeight.toFloat() / height.toFloat()
                    if (sy < scale) {
                        scale = sy
                    }
                }
                if (scale < 1f) {
                    val newWidth = (width * scale).toInt().coerceAtLeast(1)
                    val newHeight = (height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                } else {
                    bitmap
                }
            } else {
                bitmap
            }

            lastScreenWidth = screenWidth
            lastScreenHeight = screenHeight
            lastScreenshotImageWidth = targetBitmap.width
            lastScreenshotImageHeight = targetBitmap.height

            val output = ByteArrayOutputStream()
            val q = quality.coerceIn(30, 100)
            targetBitmap.compress(Bitmap.CompressFormat.JPEG, q, output)
            val bytes = output.toByteArray()

            if (SCREENSHOT_BINARY_ENABLED) {
                sendMediaBinaryFrame(MEDIA_MSG_JPEG, FRAME_FLAG_KEYFRAME, targetBitmap.width, targetBitmap.height, bytes)
            } else {
                // 旧文本通道（灰度回退用，新 APK 全量覆盖后随清理 PR 删除）
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val payload = JSONObject()
                payload.put("deviceId", deviceId())
                payload.put("contentType", "image/jpeg")
                payload.put("data", base64)
                payload.put("width", targetBitmap.width)
                payload.put("height", targetBitmap.height)
                payload.put("timestamp", System.currentTimeMillis())
                send("SCREENSHOT", payload)
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send screenshot", e)
            false
        } finally {
            // 缩放产生了新位图时也要回收，避免连续抓帧 native 内存泄漏
            if (targetBitmap != null && targetBitmap !== bitmap) {
                targetBitmap.recycle()
            }
            bitmap.recycle()
        }
    }

    private fun handleTouchEvent(payload: JSONObject) {
        val commandId = payload.optString("commandId")
        val type = payload.optString("type")
        if (type.isNullOrEmpty()) {
            Log.w(TAG, "TOUCH_EVENT missing type")
            replyCommandResult(commandId, false, "触摸指令缺少 type 字段")
            return
        }

        val startObj = payload.optJSONObject("start")
        val rawStartX = startObj?.optInt("x", -1) ?: -1
        val rawStartY = startObj?.optInt("y", -1) ?: -1
        if (rawStartX < 0 || rawStartY < 0) {
            Log.w(TAG, "TOUCH_EVENT missing valid start coordinates")
            replyCommandResult(commandId, false, "触摸指令缺少起点坐标")
            return
        }

        val endObj = payload.optJSONObject("end")
        val rawEndX = endObj?.optInt("x", -1) ?: -1
        val rawEndY = endObj?.optInt("y", -1) ?: -1
        val durationMs = payload.optLong("durationMs", 0L)

        // root 降级走 `input` 命令, 用的是真实屏幕坐标; 没有历史截图尺寸时无法做坐标映射,
        // 此时宁可拒绝也不要盲点 (图片展示尺寸未知, 坐标会错位)。
        val mappingReady = lastScreenshotImageWidth > 0 && lastScreenshotImageHeight > 0 &&
            lastScreenWidth > 0 && lastScreenHeight > 0
        val service = AutoJsAccessibilityService.instance
        if (service == null && rootAvailable && !mappingReady) {
            replyCommandResult(
                commandId,
                false,
                "root 降级触摸需要先成功获取一次截图以完成坐标映射，请先点击「请求设备截图」后再操作。$HINT_ACCESSIBILITY",
            )
            return
        }

        val (startX, startY) = mapTouchCoordinate(rawStartX, rawStartY)
        val (endX, endY) = if (rawEndX >= 0 && rawEndY >= 0) {
            mapTouchCoordinate(rawEndX, rawEndY)
        } else {
            startX to startY
        }

        performTouchGesture(
            commandId,
            type,
            startX,
            startY,
            endX,
            endY,
            durationMs,
        )
    }

    private fun mapTouchCoordinate(x: Int, y: Int): Pair<Int, Int> {
        val imageW = lastScreenshotImageWidth
        val imageH = lastScreenshotImageHeight
        val screenW = lastScreenWidth
        val screenH = lastScreenHeight
        if (imageW > 0 && imageH > 0 && screenW > 0 && screenH > 0) {
            val scaleX = screenW.toFloat() / imageW.toFloat()
            val scaleY = screenH.toFloat() / imageH.toFloat()
            val mappedX = (x * scaleX).roundToInt().coerceIn(0, screenW - 1)
            val mappedY = (y * scaleY).roundToInt().coerceIn(0, screenH - 1)
            return mappedX to mappedY
        }
        return x to y
    }

    private fun performTouchGesture(
        commandId: String?,
        type: String,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
    ) {
        val duration = when (type) {
            "long_press" -> durationMs.takeIf { it > 0 } ?: 700L
            "swipe" -> durationMs.takeIf { it > 0 } ?: 300L
            else -> durationMs.takeIf { it > 0 } ?: 120L
        }.coerceAtLeast(80L)

        val service = AutoJsAccessibilityService.instance
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                if (type == "swipe" && (startX != endX || startY != endY)) {
                    lineTo(endX.toFloat(), endY.toFloat())
                }
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val description = GestureDescription.Builder().addStroke(stroke).build()
            val ok = service.dispatchGesture(description, null, null)
            Log.d(
                TAG,
                "dispatchGesture(type=$type, start=($startX,$startY), end=($endX,$endY), duration=$duration) -> $ok",
            )
            if (ok) {
                replyCommandResult(commandId, true)
            } else {
                replyCommandResult(commandId, false, "无障碍手势派发被系统拒绝，请确认 AutoJs6 无障碍服务处于开启状态后重试")
            }
            return
        }

        // 降级: root `input` (tap / swipe; 长按用同起点 swipe 持时模拟)
        if (rootAvailable) {
            val cmd = when (type) {
                "tap" -> "input tap $startX $startY"
                "long_press" -> "input swipe $startX $startY $startX $startY ${duration.coerceAtLeast(700L)}"
                else -> "input swipe $startX $startY $endX $endY $duration"
            }
            rootExecutor.execute {
                val ok = execRoot(cmd)
                Log.d(TAG, "root touch($type) -> $ok")
                if (ok) {
                    replyCommandResult(commandId, true)
                } else {
                    replyCommandResult(commandId, false, "root 触摸指令执行失败：$cmd")
                }
            }
            return
        }

        Log.w(TAG, "TOUCH_EVENT ignored: accessibility service not running and no root")
        replyCommandResult(commandId, false, HINT_ACCESSIBILITY)
    }

    /**
     * 系统按键动作。
     *
     * @description 直接调 AccessibilityService.performGlobalAction (不再经过 bridge:
     * bridge 只在 JS Runtime 创建后才挂上, 全新设备开了无障碍也会 bridge null);
     * 无障碍不可用时在 root 设备降级为 `input keyevent`, 仍不可用才回报失败与操作指引。
     */
    private fun handleDeviceAction(payload: JSONObject) {
        val commandId = payload.optString("commandId")
        val action = payload.optString("action")
        if (action.isNullOrEmpty()) {
            Log.w(TAG, "DEVICE_ACTION missing action field")
            replyCommandResult(commandId, false, "按键指令缺少 action 字段")
            return
        }

        try {
            // 不依赖无障碍/root 的动作优先处理
            when (action) {
                "screen_on" -> {
                    runCatching { Device(appContext).wakeUpIfNeeded() }
                        .onSuccess { replyCommandResult(commandId, true) }
                        .onFailure {
                            // 个别 ROM 上 WakeLock 亮屏受限, root 下降级 KEYCODE_WAKEUP
                            runRootKeyevent(commandId, "KEYCODE_WAKEUP")
                        }
                    return
                }
                "volume_up" -> {
                    val device = Device(appContext)
                    val current = device.musicVolume
                    val max = device.musicMaxVolume
                    if (current < max) {
                        device.setMusicVolume(current + 1)
                    }
                    replyCommandResult(commandId, true)
                    return
                }
                "volume_down" -> {
                    val device = Device(appContext)
                    val current = device.musicVolume
                    if (current > 0) {
                        device.setMusicVolume(current - 1)
                    }
                    replyCommandResult(commandId, true)
                    return
                }
                "mute" -> {
                    Device(appContext).setMusicVolume(0)
                    replyCommandResult(commandId, true)
                    return
                }
            }

            // 依赖无障碍全局动作的按键
            val globalActionId = when (action) {
                "back" -> AndroidAccessibilityService.GLOBAL_ACTION_BACK
                "home" -> AndroidAccessibilityService.GLOBAL_ACTION_HOME
                "recents" -> AndroidAccessibilityService.GLOBAL_ACTION_RECENTS
                "notifications" -> AndroidAccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                "screen_off" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        AndroidAccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                    } else {
                        -1
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown DEVICE_ACTION: $action")
                    replyCommandResult(commandId, false, "未知的按键动作：$action")
                    return
                }
            }

            val service = AutoJsAccessibilityService.instance
            if (service != null && globalActionId >= 0) {
                val ok = service.performGlobalAction(globalActionId)
                Log.d(TAG, "performGlobalAction($action) -> $ok")
                if (ok) {
                    replyCommandResult(commandId, true)
                    return
                }
            }

            // 无障碍不可用/执行失败: root 键码降级 (通知栏无对应键码, 尝试 cmd statusbar)
            val keyCode = when (action) {
                "back" -> "KEYCODE_BACK"
                "home" -> "KEYCODE_HOME"
                "recents" -> "KEYCODE_APP_SWITCH"
                "screen_off" -> "KEYCODE_SLEEP"
                "notifications" -> null
                else -> null
            }
            if (keyCode != null) {
                runRootKeyevent(commandId, keyCode)
                return
            }
            if (action == "notifications" && rootAvailable) {
                rootExecutor.execute {
                    // API 31+ 模拟器/部分 ROM 支持; 不支持时 exit 非 0
                    val ok = execRoot("cmd statusbar expand-notifications")
                    if (ok) {
                        replyCommandResult(commandId, true)
                    } else {
                        replyCommandResult(commandId, false, "拉下通知栏需要开启无障碍服务（该 ROM 不支持 root 降级）。$HINT_ACCESSIBILITY")
                    }
                }
                return
            }

            Log.w(TAG, "DEVICE_ACTION $action ignored: no accessibility service")
            replyCommandResult(commandId, false, HINT_ACCESSIBILITY)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to handle DEVICE_ACTION: $action", e)
            replyCommandResult(commandId, false, "按键执行异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** root 降级执行单个键码, 并向 PC 回报结果。 */
    private fun runRootKeyevent(commandId: String?, keyCode: String) {
        if (!rootAvailable) {
            replyCommandResult(commandId, false, HINT_ACCESSIBILITY)
            return
        }
        rootExecutor.execute {
            val ok = execRoot("input keyevent $keyCode")
            Log.d(TAG, "root keyevent($keyCode) -> $ok")
            if (ok) {
                replyCommandResult(commandId, true)
            } else {
                replyCommandResult(commandId, false, "root 按键执行失败：input keyevent $keyCode")
            }
        }
    }

    fun onScriptStart(execution: ScriptExecution) {
        recordLogStart(execution)
        sendScriptStatus(execution, "running", null)
    }

    fun onScriptSuccess(execution: ScriptExecution) {
        recordLogEnd(execution)
        sendScriptStatus(execution, "success", null)
    }

    fun onScriptException(execution: ScriptExecution, e: Throwable) {
        recordLogEnd(execution)
        sendScriptStatus(execution, "error", e.message)
    }

    private fun sendScriptStatus(execution: ScriptExecution, status: String, detail: String?) {
        try {
            val scriptId = scriptIdFromExecution(execution)
            val payload = JSONObject()
            payload.put("deviceId", deviceId())
            payload.put("scriptId", scriptId)
            payload.put("status", status)
            if (!detail.isNullOrEmpty()) {
                payload.put("detail", detail)
            }
            send("SCRIPT_STATUS_UPDATE", payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send script status", e)
        }
    }

    private fun scriptIdFromExecution(execution: ScriptExecution): String {
        val source = execution.source
        return when (source) {
            is ScriptSource -> source.fullPath
            else -> source.toString()
        }
    }

    private fun recordLogStart(execution: ScriptExecution) {
        try {
            val scriptId = scriptIdFromExecution(execution)
            val console = AutoJs.instance.globalConsole
            val startIdx = synchronized(console.logEntries) { console.logEntries.size }
            synchronized(scriptLogRanges) {
                scriptLogRanges[scriptId] = ScriptLogRange(startIdx, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record log start", e)
        }
    }

    private fun recordLogEnd(execution: ScriptExecution) {
        try {
            val scriptId = scriptIdFromExecution(execution)
            val console = AutoJs.instance.globalConsole
            val endIdx = synchronized(console.logEntries) { console.logEntries.size }
            synchronized(scriptLogRanges) {
                val range = scriptLogRanges[scriptId]
                if (range == null) {
                    scriptLogRanges[scriptId] = ScriptLogRange(0, endIdx)
                } else {
                    range.endIndex = endIdx
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record log end", e)
        }
    }

    private val deviceIdLock = Any()

    @Volatile
    private var cachedDeviceId: String? = null

    /**
     * 设备稳定标识: 直接读 Settings.Secure.ANDROID_ID 并缓存。
     *
     * 禁止用 `Device(appContext).androidId` 实现: runtime.api.Device 构造时会同步调用
     * TelephonyManager.getImei / getSerial 两个 binder, 在抽屉行点击/弹窗打开等主线程路径上
     * 实测可阻塞 5s+ 触发系统 ANR (2026-09-28 模拟器堆栈实证); 且 deviceId 在渲染/建链/
     * 心跳路径高频调用, 构造开销会被放大。ANDROID_ID 首读即可, 缺失回落机型名。
     */
    fun deviceId(): String {
        cachedDeviceId?.let { return it }
        synchronized(deviceIdLock) {
            cachedDeviceId?.let { return it }
            val id = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotEmpty() }
                ?: Build.MODEL?.takeIf { it.isNotEmpty() }
                ?: "unknown"
            cachedDeviceId = id
            return id
        }
    }
}
