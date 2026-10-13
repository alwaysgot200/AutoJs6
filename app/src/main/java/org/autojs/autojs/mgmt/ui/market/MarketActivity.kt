package org.autojs.autojs.mgmt.ui.market

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.autojs.autojs.core.plugin.center.PluginInstaller
import org.autojs.autojs.mgmt.Mgmt
import org.autojs.autojs.mgmt.auth.AccountSession
import org.autojs.autojs.mgmt.client.MgmtApiHttp
import org.autojs.autojs.mgmt.client.MarketApiClient
import org.autojs.autojs.mgmt.config.MgmtServerConfig
import org.autojs.autojs.mgmt.script.MarketProjectLayout
import org.autojs.autojs.mgmt.script.MarketScriptInstaller
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

    // H5 <input type="file"> 选择器：launcher 必须在 Activity STARTED 前注册，故随字段初始化。
    private val marketChromeClient = MarketWebChromeClient(this)
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        marketChromeClient.onFileChooserResult(result.resultCode, result.data)
    }

    /** 同一时刻只允许一个安装任务 (binder 线程读, 主线程写)。 */
    @Volatile
    private var installInProgress = false

    /**
     * PLUGIN 安装协程作用域 (主线程): 调官方 [PluginInstaller] 弹 APK 信息确认并拉起系统安装器;
     * 与 Activity 生命周期绑定, onDestroy 取消。
     */
    private val pluginInstallScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
            webChromeClient = marketChromeClient
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

    /** 供 MarketWebChromeClient 拉起系统文件选择器（发帖附件 / 发布脚本包上传）。 */
    fun launchFileChooser(intent: Intent) {
        fileChooserLauncher.launch(intent)
    }

    /**
     * 桥入口 (binder 线程): 解析 `{scriptId, licenseMode?, packageType?}` →
     * 忙等/权限门禁 → 后台下载 → SINGLE/PROJECT 原子解压安装、PLUGIN 主线程拉起系统安装器 → 回派结果事件。
     *
     * packageType 缺省按脚本包处理 (旧版门户行为不变); PLUGIN 不需要 AllFilesAccess 权限
     * (APK 不写脚本工作目录), 故跳过该门禁。
     */
    fun requestInstall(payload: String?) {
        val parsed = runCatching {
            val obj = JSONObject(payload.orEmpty())
            val id = obj.getInt("scriptId").takeIf { it > 0 } ?: return@runCatching null
            val pkgType = obj.optString("packageType").trim().uppercase()
            id to pkgType
        }.getOrNull()
        if (parsed == null) {
            dispatchInstallResult(false, getString(R.string.mgmt_market_bad_args))
            return
        }
        val (scriptId, payloadPackageType) = parsed
        val isPlugin = payloadPackageType == "PLUGIN"
        runOnUiThread {
            if (installInProgress) {
                dispatchInstallResult(false, getString(R.string.mgmt_market_busy))
                return@runOnUiThread
            }
            // MANAGE_EXTERNAL_STORAGE: 写脚本工作目录必需; PLUGIN 走系统安装器, 无需此权限。
            if (!isPlugin && !AllFilesAccessPermission(this).has()) {
                runCatching { AllFilesAccessPermission(this).request() }
                dispatchInstallResult(false, getString(R.string.mgmt_market_storage_required))
                return@runOnUiThread
            }
            installInProgress = true
            ViewUtils.showToast(
                this,
                if (isPlugin) R.string.mgmt_market_plugin_download_started else R.string.mgmt_market_download_started,
            )
            MgmtApiHttp.executor.execute {
                runCatching { MarketApiClient.downloadBlocking(applicationContext, scriptId) }
                    .onSuccess { pkg ->
                        if (pkg.packageType == "PLUGIN") {
                            // 系统 PackageInstaller 必须在主线程经协程调起, 下载线程不得碰 UI。
                            MgmtApiHttp.mainHandler.post { installPluginOnMain(pkg) }
                        } else {
                            val installed = runCatching { MarketScriptInstaller.installBlocking(pkg) }
                            MgmtApiHttp.mainHandler.post {
                                installInProgress = false
                                installed
                                    .onSuccess { info ->
                                        val msg = getString(R.string.mgmt_market_install_success, info.relativePath)
                                        ViewUtils.showToast(this, msg)
                                        dispatchInstallResult(true, msg)
                                    }
                                    .onFailure { err -> handleInstallFailure(err) }
                            }
                        }
                    }
                    .onFailure { err -> MgmtApiHttp.mainHandler.post { handleInstallFailure(err) } }
            }
        }
    }

    /**
     * PLUGIN 主线程安装 (官方 [PluginInstaller]):
     * 先经官方 APK 信息确认弹窗, 用户确认后拉起系统 PackageInstaller。
     * 官方入口在弹窗成功展示/异常时即返回 (系统安装最终结果应用内不可知),
     * 故「已拉起」按成功回派; 官方自身已对异常弹失败对话框, 此处不重复弹窗。
     * 临时 APK 保留在 cacheDir/plugin_dl/ (系统安装器需持续读取, 交系统缓存清理)。
     */
    private fun installPluginOnMain(pkg: MarketApiClient.PackageFile) {
        pluginInstallScope.launch {
            val uri = runCatching { MarketScriptInstaller.preparePluginUri(this@MarketActivity, pkg) }
                .getOrElse { err ->
                    installInProgress = false
                    handleInstallFailure(err)
                    return@launch
                }
            val result = PluginInstaller.installFromFileUriWithPrompt(this@MarketActivity, uri)
            installInProgress = false
            result
                .onSuccess {
                    val msg = getString(R.string.mgmt_market_plugin_ready)
                    ViewUtils.showToast(this@MarketActivity, msg)
                    dispatchInstallResult(true, msg)
                }
                .onFailure { err ->
                    Log.w(TAG, "plugin install failed", err)
                    dispatchInstallResult(
                        false,
                        err.message?.takeIf { it.isNotEmpty() }
                            ?: getString(R.string.mgmt_market_install_failed_generic),
                    )
                }
        }
    }

    /** 下载/校验/安装失败统一收口: 长指引对话框 + 回派 false 事件 (主线程调用)。 */
    private fun handleInstallFailure(err: Throwable) {
        installInProgress = false
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
        marketChromeClient.abortPending()
        pluginInstallScope.cancel()
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
