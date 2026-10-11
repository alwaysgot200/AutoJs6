package org.autojs.autojs.mgmt.ui.market

import android.webkit.JavascriptInterface
import org.autojs.autojs.mgmt.auth.AccountSession
import org.autojs.autojs.mgmt.config.MgmtServerConfig

/**
 * 门户页 ↔ 原生桥 (方案 §10.1 MarketWebBridge, 注入名 `window.MgmtBridge`)。
 *
 * 仅暴露最小能力面; 全部方法经 [MarketActivity] 在主线程编排, 下载/安装在后台线程:
 * - [downloadAndInstall] 业务动作: 入参 JSON `{scriptId, licenseMode?}`;
 * - [getToken] / [getRefreshToken] / [getServerBase] 门户启动同步会话/识别环境 (P5);
 * - [openLogin] / [openRegister] 未登录互动的原生收口 (注册只走系统浏览器);
 * - [nativeLogout] 门户退出登录的原生收口 (P5, 清凭证+断 WS 后由 Activity reload)。
 *
 * @JavascriptInterface 方法运行在 WebKit 后台 binder 线程, 不得直接操作 UI。
 */
internal class MarketWebBridge(private val activity: MarketActivity) {

    @JavascriptInterface
    fun isBusy(): Boolean = activity.isInstallBusy()

    @JavascriptInterface
    fun downloadAndInstall(payload: String?) {
        activity.requestInstall(payload)
    }

    @JavascriptInterface
    fun getToken(): String? = AccountSession.accessToken()

    @JavascriptInterface
    fun getRefreshToken(): String? = AccountSession.refreshToken()

    @JavascriptInterface
    fun getServerBase(): String = MgmtServerConfig.serverBaseUrl()

    @JavascriptInterface
    fun openLogin() {
        activity.showNativeLogin()
    }

    @JavascriptInterface
    fun openRegister() {
        activity.openSystemRegister()
    }

    /**
     * P5: 门户顶栏「退出登录」唯一原生收口: 清 Keystore 凭证 + matchCode + 断 WS/FGS。
     * 完成后 [MarketActivity] 经 AccountSession.Listener 收到登出事件并 reload,
     * 门户启动同步随即清空 WebView localStorage, 回到游客态。
     */
    @JavascriptInterface
    fun nativeLogout() {
        activity.nativeLogout()
    }
}
