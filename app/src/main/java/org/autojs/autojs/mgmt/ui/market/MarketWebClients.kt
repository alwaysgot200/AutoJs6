package org.autojs.autojs.mgmt.ui.market

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
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

/** 默认 Chrome 客户端占位: P3 无自定义需求, 预留文件选择/全屏视频扩展位, 不在 Activity 内联。 */
internal class MarketWebChromeClient : WebChromeClient()
