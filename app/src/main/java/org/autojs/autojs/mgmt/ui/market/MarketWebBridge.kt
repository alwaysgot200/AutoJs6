package org.autojs.autojs.mgmt.ui.market

import android.webkit.JavascriptInterface
import org.autojs.autojs.mgmt.auth.AccountSession
import org.autojs.autojs.mgmt.config.MgmtServerConfig

/**
 * 门户页 ↔ 原生桥 (方案 §10.1 MarketWebBridge, 注入名 `window.MgmtBridge`)。
 *
 * 仅暴露最小能力面; 全部方法经 [MarketActivity] 在主线程编排, 下载/安装在后台线程:
 * - [downloadAndInstall] P3 唯一业务动作: 入参 JSON `{scriptId, licenseMode?}`;
 * - [getToken] / [getServerBase] 供门户页识别环境/将来 P4 接口复用;
 * - [openLogin] / [openRegister] 未登录互动的原生收口 (注册只走系统浏览器)。
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
    fun getServerBase(): String = MgmtServerConfig.serverBaseUrl()

    @JavascriptInterface
    fun openLogin() {
        activity.showNativeLogin()
    }

    @JavascriptInterface
    fun openRegister() {
        activity.openSystemRegister()
    }
}
