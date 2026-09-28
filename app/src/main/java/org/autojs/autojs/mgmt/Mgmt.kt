package org.autojs.autojs.mgmt

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.autojs.autojs.engine.ScriptEngineService
import org.autojs.autojs.mgmt.client.ManagementPlatformClient
import org.autojs.autojs.mgmt.perm.MgmtPermissionRequester
import org.autojs.autojs.mgmt.script.ManagementPlatformScriptExecutionListener
import org.autojs.autojs.mgmt.service.ManagementPlatformService

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
        MgmtPermissionRequester.register(application)
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
}
