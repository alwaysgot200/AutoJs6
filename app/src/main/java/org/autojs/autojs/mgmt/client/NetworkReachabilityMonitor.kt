package org.autojs.autojs.mgmt.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * 本机网络可达性监视器 (跨境弱网优化 P0, mgmt 自有文件)。
 *
 * 职责单一:
 * - 维护"本机是否存在具备 Internet 能力的网络" ([isOnline]);
 * - 在线状态从无到有时经 [listener] 立即通知, 让 ManagementPlatformClient 取消退避、
 *   即时重连——把恢复延迟从"最坏等待一个退避周期 (60s)"降到 1~2s;
 * - 网络丢失时仅更新状态, 不主动关闭连接 (onLost 可能只是 WiFi↔蜂窝切换中的瞬态)。
 *
 * **刻意不要求 NET_CAPABILITY_VALIDATED**: 系统校验依赖 Google 连通性探针,
 * 在中国大陆网络常被拦截, 会出现"网络实际可用但永远未验证"——那将导致原因被
 * 永久误判为"本机断网"且在线事件不触发、即时重连失效。真正的可达性由 WS 鉴权
 * 结果 (AUTH_OK) 证明; 偶发的假阳性最多造成一次快速失败的连接尝试, 代价远小于假阴性。
 *
 * 生命周期: 进程级单例, Mgmt#bootstrap (H2) 时启动; 内部回调持 application context,
 * 不存在 Activity 泄漏面。回调可能来自 binder 线程, 调用方需自行保证线程安全。
 */
class NetworkReachabilityMonitor private constructor(context: Context) {

    fun interface Listener {
        fun onLocalNetworkStateChanged(online: Boolean)
    }

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    var isOnline: Boolean = false
        private set

    private var listener: Listener? = null
    private var registered = false

    private val trackedNetworks = mutableMapOf<Network, Boolean>() // network -> internet capable

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // 先以"可用"登记 (默认回调中的网络通常具备 Internet 能力),
            // 保证大陆环境下即便能力回调延迟也能尽快触发即时重连。
            val wasOnline = isOnline
            synchronized(trackedNetworks) {
                trackedNetworks.putIfAbsent(network, true)
                recomputeOnlineLocked()
            }
            dispatchIfChanged(wasOnline)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val wasOnline = isOnline
            synchronized(trackedNetworks) {
                trackedNetworks[network] = usable
                recomputeOnlineLocked()
            }
            dispatchIfChanged(wasOnline)
        }

        override fun onLost(network: Network) {
            val wasOnline = isOnline
            synchronized(trackedNetworks) {
                trackedNetworks.remove(network)
                recomputeOnlineLocked()
            }
            dispatchIfChanged(wasOnline)
        }
    }

    private fun recomputeOnlineLocked() {
        isOnline = trackedNetworks.values.any { it }
    }

    private fun dispatchIfChanged(wasOnline: Boolean) {
        if (wasOnline == isOnline) return
        Log.i(TAG, "local network state changed: online=$isOnline")
        runCatching { listener?.onLocalNetworkStateChanged(isOnline) }
    }

    private fun initialOnline(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * 注册系统网络回调。重复调用安全; 任何异常都不得穿透到宿主启动流程。
     */
    @Synchronized
    fun start(listener: Listener) {
        this.listener = listener
        if (registered) return
        runCatching {
            // registerDefaultNetworkCallback (API 24+, minSdk=24) 接收所有网络的能力变化,
            // 无需声明 NetworkRequest 过滤条件, 最贴合"任意网络恢复即重连"的需求。
            cm.registerDefaultNetworkCallback(callback)
            registered = true
        }.onSuccess {
            // 初始状态异步读取: getNetworkCapabilities 是 binder 调用, 严禁在
            // Application#onCreate 主线程同步执行 (本仓已有 getDhcpInfo 同类 ANR 教训)。
            // 默认 false 偏保守, 后台读到真实状态后立即纠正; 当前无网时系统不会回调
            // onAvailable, 这个一次性异步读取也保证 offline 态被正确初始化。
            initExecutor.execute {
                val now = runCatching { initialOnline() }.getOrDefault(false)
                val wasOnline = isOnline
                isOnline = now
                dispatchIfChanged(wasOnline)
            }
        }.onFailure {
            // 极个别厂商 ROM 可能限制回调注册: 退化为不监听 (退避重连仍可兜底)。
            Log.w(TAG, "registerDefaultNetworkCallback failed: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "MgmtNetMonitor"

        /**
         * 仅用于首次状态读取的单线程守护池 (一次 binder 查询即结束),
         * 避免 Application#onCreate 主线程同步 binder。
         */
        private val initExecutor: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "mgmt-net-init").apply { isDaemon = true }
            }

        @Volatile
        private var instance: NetworkReachabilityMonitor? = null

        @JvmStatic
        fun start(context: Context, listener: Listener): NetworkReachabilityMonitor {
            val monitor = instance ?: synchronized(this) {
                instance ?: NetworkReachabilityMonitor(context).also { instance = it }
            }
            monitor.start(listener)
            return monitor
        }

        /**
         * 查询当前是否存在具备 Internet 能力的本机网络。
         * 注意命名刻意区别于实例属性 [isOnline], 避免 JVM 签名冲突。
         */
        @JvmStatic
        fun currentlyOnline(): Boolean = instance?.isOnline ?: false
    }
}
