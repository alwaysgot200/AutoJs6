package org.autojs.autojs.mgmt.ui.market

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.autojs.autojs.mgmt.config.MgmtServerConfig

/**
 * 市场 WebView 客户端集合 (方案 §10.1, mgmt 自有文件)。
 *
 * 导航收口规则:
 * - 平台同源页面 (脚本市场/技术论坛/详情) 一律 WebView 内打开;
 * - `/admin/login` 不允许在 WebView 内渲染登录页: 唤起 mgmt 原生账号登录弹窗;
 * - `/admin/register` 不允许在 WebView 内渲染注册页: 一律系统浏览器 (方案 §7.1 硬规则);
 * - 外部站点 / mailto / tel / intent 等: 系统浏览器或对应应用, WebView 不兜底。
 */
internal class MarketWebViewClient(private val activity: MarketActivity) : WebViewClient() {

    private val platformHost: String = Uri.parse(MgmtServerConfig.serverBaseUrl()).host.orEmpty()

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return true
        val path = uri.path.orEmpty()

        if (path.startsWith("/admin/login")) {
            activity.showNativeLogin()
            return true
        }
        if (path.startsWith("/admin/register")) {
            activity.openSystemRegister()
            return true
        }

        val scheme = uri.scheme.orEmpty().lowercase()
        if (scheme != "http" && scheme != "https") {
            activity.openExternally(uri)
            return true
        }
        // 跨主机链接 (文档站外链/第三方) 交给系统浏览器, WebView 只承载平台同源页。
        if (uri.host.isNullOrEmpty() || uri.host != platformHost) {
            activity.openExternally(uri)
            return true
        }
        return false
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        activity.onMarketPageChanged(url)
    }
}

/**
 * 市场 Chrome 客户端 (mgmt 自有文件):
 *
 * 实现 H5 `<input type="file">` 文件选择 —— 论坛附件上传、脚本/工程包发布上传都依赖此能力。
 * 默认空 WebChromeClient 在 Android WebView 上点击文件控件会静默无反应。
 * 选择器经 [MarketActivity] 的 ActivityResultLauncher 拉起, 结果 uri 数组回传给 WebView;
 * content:// uri 的临时读权限授予本应用 uid, Chromium 网络栈（同进程）可直接读取上传。
 */
internal class MarketWebChromeClient(private val activity: MarketActivity) : WebChromeClient() {

    private companion object {
        const val TAG = "MgmtMarket"
    }

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /**
     * ActivityResult 回调: 选择成功/取消都必须回调 WebView,
     * 取消（resultCode != OK 或 data 为 null）时 [FileChooserParams.parseResult] 返回 null,
     * 以 null 回调才能释放上传通道, 否则后续点击文件控件永久无响应。
     */
    fun onFileChooserResult(resultCode: Int, data: Intent?) {
        val callback = filePathCallback ?: return
        filePathCallback = null
        callback.onReceiveValue(FileChooserParams.parseResult(resultCode, data))
    }

    /** Activity 销毁时释放仍在等待的回调, 避免 WebView 悬挂。 */
    fun abortPending() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
    }

    override fun onShowFileChooser(
        webView: WebView?,
        callback: ValueCallback<Array<Uri>>,
        params: FileChooserParams,
    ): Boolean {
        // 上一次选择尚未结束又触发（重复点击/异常）时先释放旧回调
        filePathCallback?.onReceiveValue(null)
        filePathCallback = callback

        return try {
            val contentIntent = params.createIntent()
            // accept=".auto" 等扩展名在部分文件管理器中可能过滤为空, 附 */* 兜底入口保证可选文件
            val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
            val chooserIntent = Intent.createChooser(contentIntent, null).apply {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(fallbackIntent))
            }
            activity.launchFileChooser(chooserIntent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity to handle web file chooser", e)
            filePathCallback = null
            callback.onReceiveValue(null)
            false
        }
    }
}
