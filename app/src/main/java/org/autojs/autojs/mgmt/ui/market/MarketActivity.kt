package org.autojs.autojs.mgmt.ui.market

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import org.autojs.autojs.mgmt.Mgmt
import org.autojs.autojs.mgmt.auth.AccountSession
import org.autojs.autojs.mgmt.client.MgmtApiHttp
import org.autojs.autojs.mgmt.client.MarketApiClient
import org.autojs.autojs.mgmt.config.MgmtServerConfig
import org.autojs.autojs.mgmt.script.MarketScriptInstaller
import org.autojs.autojs.mgmt.script.MarketProjectLayout
import org.autojs.autojs.permission.AllFilesAccessPermission
import org.autojs.autojs.util.ViewUtils
import org.autojs.autojs6.R
import org.json.JSONObject

/**
 * 脚本市场容器 (方案 §10.1/§10.2, P3): 顶部双 tab (脚本市场/技术论坛) + 单 WebView。
 *
 * - 同源相对路径, dev/release 地址一律取自 [MgmtServerConfig] (不硬编码内部端口);
 * - 注入 [MarketWebBridge] (`window.MgmtBridge`), 门户页「立即安装」走原生
 *   带 JWT + X-Device-Id 下载, sha256 校验后原子安装 (见 [MarketScriptInstaller]);
 * - 登录/注册在 WebView 内收口 (原生登录弹窗/系统浏览器), 不渲染平台登录注册页;
 * - 薄层异常不得崩溃宿主: 所有桥回调吞异常并把结果回派给门户页 toast。
 */
class MarketActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MgmtMarket"
    }

    private enum class Tab { SCRIPTS, FORUM }

    private lateinit var tabs: TabLayout
    private lateinit var webContainer: FrameLayout
    private var webView: WebView? = null
    private var currentTab: Tab = Tab.SCRIPTS
    private var tabSwitchEnabled = false

    /** 同一时刻只允许一个安装任务 (binder 线程读, 主线程写)。 */
    @Volatile
    private var installInProgress = false

    fun isInstallBusy(): Boolean = installInProgress

    /**
     * 原生账号状态变化后刷新当前页 (登录/退出都在 WebView 外完成, 见 MarketWebViewClient 收口):
     * reload 使门户启动同步重新对齐 localStorage 会话 —— 登录后拿到 JWT, 退出后清空,
     * 避免顶栏/页面停留在旧账号态。
     */
    private val accountListener = AccountSession.Listener {
        webView?.post { runCatching { webView?.reload() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.mgmt_activity_market)

        tabs = findViewById(R.id.mgmt_market_tabs)
        webContainer = findViewById(R.id.mgmt_market_container)

        tabs.addTab(tabs.newTab().setText(R.string.mgmt_market_tab_scripts))
        tabs.addTab(tabs.newTab().setText(R.string.mgmt_market_tab_forum))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (!tabSwitchEnabled) return
                val target = if (tab.position == 0) Tab.SCRIPTS else Tab.FORUM
                if (target != currentTab) loadTab(target)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        setupWebView()
        AccountSession.addListener(accountListener)
        tabSwitchEnabled = true
        loadTab(Tab.SCRIPTS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val view = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                // 市场页无需也不允许访问本地文件/内容。
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = true
                userAgentString = "$userAgentString MgmtMarket/1.0"
            }
            webViewClient = MarketWebViewClient(this@MarketActivity)
            webChromeClient = MarketWebChromeClient()
            // 必须在首次 loadUrl 前注入; 门户页据此切换「免费下载/立即安装」按钮。
            addJavascriptInterface(MarketWebBridge(this@MarketActivity), "MgmtBridge")
        }
        webContainer.addView(view)
        webView = view
    }

    private fun loadTab(tab: Tab) {
        currentTab = tab
        val path = if (tab == Tab.SCRIPTS) "/admin/market/shop" else "/admin/market/forum"
        webView?.loadUrl(MgmtServerConfig.serverBaseUrl() + path)
    }

    /** WebViewClient 页面切换回调: 同步 tab 高亮但不触发二次加载。 */
    fun onMarketPageChanged(url: String?) {
        val isForum = url?.contains("/market/forum") == true
        val target = if (isForum) Tab.FORUM else Tab.SCRIPTS
        if (target != currentTab) {
            currentTab = target
            tabSwitchEnabled = false
            tabs.getTabAt(if (isForum) 1 else 0)?.select()
            tabSwitchEnabled = true
        }
    }

    // region 桥接动作

    fun showNativeLogin() {
        runOnUiThread { runCatching { Mgmt.showLoginDialog(this) } }
    }

    fun openSystemRegister() {
        runOnUiThread { runCatching { Mgmt.openRegister(applicationContext) } }
    }

    /**
     * P5 桥入口: 门户顶栏「退出登录」。复用抽屉同一编排
     * (服务端拉黑最佳努力 → 清 Keystore 凭证/matchCode → 断 WS/停 FGS → toast)，
     * 完成后 [accountListener] 触发 reload，门户回到游客态。
     */
    fun nativeLogout() {
        runOnUiThread { runCatching { Mgmt.logout(applicationContext) } }
    }

    fun openExternally(uri: Uri) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * 桥入口 (binder 线程): 解析 `{scriptId}` → 忙等/权限门禁 → 后台下载+安装 → 回派结果事件。
     */
    fun requestInstall(payload: String?) {
        val scriptId = runCatching {
            JSONObject(payload.orEmpty()).getInt("scriptId")
        }.getOrNull()
        if (scriptId == null || scriptId <= 0) {
            dispatchInstallResult(false, getString(R.string.mgmt_market_bad_args))
            return
        }
        runOnUiThread {
            if (installInProgress) {
                dispatchInstallResult(false, getString(R.string.mgmt_market_busy))
                return@runOnUiThread
            }
            // MANAGE_EXTERNAL_STORAGE: 写脚本工作目录必需; 未授权时引导去设置, 不静默失败。
            if (!AllFilesAccessPermission(this).has()) {
                runCatching { AllFilesAccessPermission(this).request() }
                dispatchInstallResult(false, getString(R.string.mgmt_market_storage_required))
                return@runOnUiThread
            }
            installInProgress = true
            ViewUtils.showToast(this, R.string.mgmt_market_download_started)
            MgmtApiHttp.executor.execute {
                val result = runCatching {
                    val pkg = MarketApiClient.downloadBlocking(applicationContext, scriptId)
                    MarketScriptInstaller.installBlocking(pkg)
                }
                MgmtApiHttp.mainHandler.post {
                    installInProgress = false
                    result
                        .onSuccess { info ->
                            val msg = getString(R.string.mgmt_market_install_success, info.relativePath)
                            ViewUtils.showToast(this, msg)
                            dispatchInstallResult(true, msg)
                        }
                        .onFailure { err ->
                            val msg = err.message?.takeIf { it.isNotEmpty() }
                                ?: getString(R.string.mgmt_market_install_failed_generic)
                            val friendly = when (err) {
                                is MarketApiClient.DownloadException,
                                is MarketProjectLayout.InvalidPackageException -> msg
                                else -> "${getString(R.string.mgmt_market_install_failed_generic)}: $msg"
                            }
                            // P5: 失败常含完整行动指引 (7154 续费 / 7158 移除席位 /
                            // 7166 联系客服解绑 / 7167 改用 APP), toast 时长不足以读完, 用对话框。
                            val bizCode = (err as? MarketApiClient.DownloadException)?.bizCode
                            Log.w(TAG, "market install failed: bizCode=$bizCode", err)
                            showInstallFailureDialog(friendly)
                            dispatchInstallResult(false, friendly)
                        }
                }
            }
        }
    }

    /**
     * 下载/安装失败指引对话框 (P5): 文案为后端中文指引或本地校验原因,
     * 不可点外部取消, 必须显式确认, 避免长指引在 toast 中一闪而过。
     * 对话框自身异常 (窗口泄漏等) 吞掉, 不影响结果事件回派。
     */
    private fun showInstallFailureDialog(message: String) {
        runCatching {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.mgmt_market_failed_title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(R.string.mgmt_market_failed_confirm, null)
                .show()
        }
    }

    /** 向门户页派发安装结果 (与页面 CustomEvent 监听契约一致)。 */
    private fun dispatchInstallResult(ok: Boolean, message: String) {
        val okLiteral = if (ok) "true" else "false"
        val js = "window.dispatchEvent(new CustomEvent('mgmt:install-result'," +
            "{detail:{ok:$okLiteral,message:${JSONObject.quote(message)}}}))"
        runOnUiThread { runCatching { webView?.evaluateJavascript(js, null) } }
    }

    // endregion

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) {
            view.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        AccountSession.removeListener(accountListener)
        runCatching {
            webView?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
        }
        webView = null
        super.onDestroy()
    }
}
