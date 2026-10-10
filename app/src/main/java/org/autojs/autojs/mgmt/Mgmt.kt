package org.autojs.autojs.mgmt

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.autojs.autojs.engine.ScriptEngineService
import org.autojs.autojs.mgmt.auth.AccountSession
import org.autojs.autojs.mgmt.client.AccessCodeClient
import org.autojs.autojs.mgmt.client.ManagementPlatformClient
import org.autojs.autojs.mgmt.client.NetworkReachabilityMonitor
import org.autojs.autojs.mgmt.config.MgmtServerConfig
import org.autojs.autojs.mgmt.perm.MgmtPermissionRequester
import org.autojs.autojs.mgmt.pref.MgmtPref
import org.autojs.autojs.mgmt.script.ManagementPlatformScriptExecutionListener
import org.autojs.autojs.mgmt.service.ManagementPlatformService
import org.autojs.autojs.mgmt.ui.MgmtPlatformLoginMenu
import org.autojs.autojs.ui.main.drawer.DrawerMenuItem
import org.autojs.autojs.util.ViewUtils
import org.autojs.autojs6.R

/**
 * 管理平台薄层唯一门面 (详见 MGMT_LAYER.md)。
 *
 * 上游代码只允许通过本 object 与薄层交互, 每个挂钩点一行委托:
 * - H2 App#onCreate (主进程) -> [bootstrap]
 * - H3 AutoJs init          -> [onEngineReady]
 * - H4 AccessibilityService  -> [onAccessibilityStateChanged] (连接/断开两处)
 * - H5 BaseBroadcastReceiver -> [connectIfConfigured]
 */
object Mgmt {

    private const val TAG = "Mgmt"

    /**
     * FGS 启动被系统拒绝时的重试延迟 (毫秒)。
     *
     * 典型场景: force-stop 后由非 Launcher 入口 (monkey/广播) 唤起, Application#onCreate
     * 时机 mAllowStartForeground=false; 桌面启动后 Launcher Activity resume 即可放行,
     * 故按递增延迟在进程存活期间重试, 成功即止。
     */
    private val FGS_RETRY_DELAYS_MS = longArrayOf(2_000L, 8_000L, 20_000L, 60_000L)

    /**
     * 应用启动 (主进程) 时调用: 注册运行时权限申请并以前台服务方式拉起平台连接。
     * 服务内部会校验地址/秘钥完整性, 未配置时自行退出。
     *
     * 任何启动失败都必须被吞掉并降级重试 —— 薄层绝不能拖垮宿主 Application#onCreate。
     */
    @JvmStatic
    fun bootstrap(application: Application) {
        // 先恢复账号会话 (Keystore 解密 JWT), 后续 FGS 建连门禁依赖登录态。
        AccountSession.restore()
        MgmtPermissionRequester.register(application)
        // 本机网络恢复即时重连 (回调内部自行吞异常, 绝不拖垮宿主启动流程)。
        runCatching {
            NetworkReachabilityMonitor.start(
                application,
                ManagementPlatformClient::onLocalNetworkStateChanged,
            )
        }
        tryStartManagementService(application, attempt = 0)
    }

    private fun tryStartManagementService(application: Application, attempt: Int) {
        val started = runCatching {
            val intent = Intent(application, ManagementPlatformService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                application.startForegroundService(intent)
            } else {
                application.startService(intent)
            }
        }.onFailure {
            // ForegroundServiceStartNotAllowedException (API 31+) 等: 不允许穿透到宿主启动流程。
            Log.w(TAG, "ManagementPlatformService start rejected (attempt=$attempt): ${it.message}")
        }.isSuccess

        if (started || attempt >= FGS_RETRY_DELAYS_MS.size) {
            return
        }
        Handler(Looper.getMainLooper()).postDelayed(
            { tryStartManagementService(application, attempt + 1) },
            FGS_RETRY_DELAYS_MS[attempt],
        )
    }

    /**
     * 脚本引擎初始化完成后注册脚本生命周期监听器 (运行态上报)。
     */
    @JvmStatic
    fun onEngineReady(scriptEngineService: ScriptEngineService) {
        scriptEngineService.registerGlobalScriptExecutionListener(
            ManagementPlatformScriptExecutionListener(),
        )
    }

    /**
     * 无障碍服务连接/断开状态变化时通知客户端 (能力回推与 root 自愈)。
     */
    @JvmStatic
    fun onAccessibilityStateChanged() {
        runCatching { ManagementPlatformClient.onAccessibilityStateChanged() }
    }

    /**
     * 开机/广播唤起等时机尝试建立平台连接 (配置不完整时客户端内部直接返回)。
     */
    @JvmStatic
    fun connectIfConfigured() {
        runCatching { ManagementPlatformClient.connectIfConfigured() }
    }

    /**
     * H9: 在官方抽屉菜单最前插入「账号」分区 (group + 状态化条目), 逻辑见 [MgmtPlatformLoginMenu]。
     */
    @JvmStatic
    fun decorateDrawerMenuItems(items: List<DrawerMenuItem>): List<DrawerMenuItem> =
        MgmtPlatformLoginMenu.decorate(items)

    /**
     * 打开脚本市场容器 (方案 §10.1, P3): 双 tab WebView (脚本市场/技术论坛)。
     * 市场浏览/免费下载无需登录; 未登录互动由 MarketActivity 内部收口到原生登录。
     */
    @JvmStatic
    fun openMarket(context: Context) {
        runCatching {
            val intent = Intent(context, org.autojs.autojs.mgmt.ui.market.MarketActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            Log.w(TAG, "open market failed: ${it.message}")
            ViewUtils.showToast(context, it.message ?: "open market failed", true)
        }
    }

    /**
     * 唤起原生账号登录弹窗 (P1 既有弹窗, P3 供市场 WebView 拦截 /admin/login 时复用)。
     */
    @JvmStatic
    fun showLoginDialog(context: Context) {
        MgmtPlatformLoginMenu.showLoginDialog(context)
    }

    /**
     * 抽屉登录弹窗测试通过后拉起前台服务建立正式持久连接 (与设置页入口同一行为)。
     */
    @JvmStatic
    fun startManagementService(context: Context) {
        runCatching {
            val intent = Intent(context, ManagementPlatformService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure {
            Log.w(TAG, "ManagementPlatformService start rejected: ${it.message}")
        }
    }

    /**
     * 用户主动「连接设备」(方案 §7.1, 连接与会话独立):
     * 已登录但接入码缺失/上次拉取失败时先现场调 /device/access-code/mine 补齐,
     * 随后放开客户端连接开关并拉起前台服务。全部回调均在主线程。
     */
    @JvmStatic
    fun connectDevice(context: Context) {
        if (!AccountSession.isLoggedIn) {
            ViewUtils.showToast(context, R.string.mgmt_text_session_expired)
            return
        }
        if (MgmtPref.secret.isNotEmpty()) {
            ManagementPlatformClient.userConnect()
            startManagementService(context)
            return
        }
        ViewUtils.showToast(context, R.string.mgmt_text_access_code_fetching)
        AccountSession.ensureAccessCode { result ->
            result
                .onSuccess {
                    ManagementPlatformClient.userConnect()
                    startManagementService(context)
                }
                .onFailure { err ->
                    val msg = if (err is AccessCodeClient.AccessCodeException &&
                        err.bizCode == AccessCodeClient.BIZ_CODE_PLATFORM_UNSUPPORTED
                    ) {
                        context.getString(R.string.mgmt_text_access_code_platform)
                    } else {
                        context.getString(
                            R.string.mgmt_text_access_code_failed,
                            err.message ?: err.javaClass.simpleName,
                        )
                    }
                    ViewUtils.showToast(context, msg, true)
                }
        }
    }

    /**
     * 用户主动「断开连接」: 只断开 WS/停止前台服务, **不清除登录态** —— 账号保持登录,
     * 下次「连接设备」或 App 重启 (bootstrap → FGS) 时自动恢复在线。
     */
    @JvmStatic
    fun disconnectDevice(context: Context) {
        ManagementPlatformClient.userDisconnect()
        runCatching { context.stopService(Intent(context, ManagementPlatformService::class.java)) }
    }

    /**
     * 免费注册唯一通道 (方案 §7.1/A13): APP 内零注册表单、WebView 不加载注册页,
     * 一律经系统浏览器打开平台注册页 (/admin/register); 一期无 deep link 回跳。
     */
    @JvmStatic
    fun openRegister(context: Context) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(MgmtServerConfig.registerPageUrl()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ViewUtils.showToast(context, R.string.mgmt_text_register_open_browser)
        }.onFailure {
            Log.w(TAG, "open register page failed: ${it.message}")
            ViewUtils.showToast(context, it.message ?: "open browser failed", true)
        }
    }

    /**
     * 退出登录 (方案 §10.1): 通知服务端拉黑令牌 (最佳努力) → 清除全部本地凭证
     * (JWT + 租户接入码) → 取消重连并关闭 WS、停止前台服务。
     * 顺序保证: AccountSession 回调在凭证清理完成后执行, close 触发的 onClosed 因
     * 连接开关已关而立即跳过重连, START_STICKY 重建服务也因未登录 stopSelf。
     */
    @JvmStatic
    fun logout(context: Context) {
        AccountSession.logout {
            ManagementPlatformClient.logout()
            runCatching { context.stopService(Intent(context, ManagementPlatformService::class.java)) }
            ViewUtils.showToast(context, R.string.mgmt_text_logged_out)
        }
    }

    /**
     * P3: 唤醒/解锁 Activity 已真实创建。
     *
     * @returns true=启动 token 有效，Activity 可继续执行并上报；
     *          false=token 已失败/未知（含薄层自身异常），Activity 须静默退出
     */
    @JvmStatic
    fun onWakeUnlockActivityCreated(launchToken: Int): Boolean =
        runCatching { ManagementPlatformClient.onWakeUnlockActivityCreated(launchToken) }.getOrDefault(false)

    /**
     * P3: 唤醒/解锁 Activity 最终结果经门面回报给 client 发 COMMAND_RESULT。
     */
    @JvmStatic
    fun reportWakeUnlockResult(commandId: String?, success: Boolean, message: String?) {
        runCatching { ManagementPlatformClient.reportWakeUnlockResult(commandId, success, message) }
    }
}
