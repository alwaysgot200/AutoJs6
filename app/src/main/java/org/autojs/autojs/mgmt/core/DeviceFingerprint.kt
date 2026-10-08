package org.autojs.autojs.mgmt.core

import android.os.Build
import android.provider.Settings
import org.autojs.autojs.app.GlobalAppContext

/**
 * 统一设备指纹 (方案 §10.2-8):
 *
 * 市场下载核销 (X-Device-Id) 与 WS 设备注册 (deviceId query) 必须取同一值 ——
 * Settings.Secure.ANDROID_ID。API 26+ 起 ANDROID_ID 按「应用签名 + 设备 + 用户」
 * 稳定, 卸载重装不变, 因此 DEVICE 单首装绑定后重装可直接放行、不重复占席。
 *
 * 进程内缓存 (首读为 ContentResolver binder, 抽屉渲染/建链/心跳高频调用)。
 * 禁止用 runtime.api.Device(context).androidId: 其构造同步调用
 * TelephonyManager.getImei/getSerial 两个 binder, 主线程路径实测阻塞 5s+ 触发 ANR。
 * 极端设备取不到时回落机型名, 再缺省 "unknown"。
 */
object DeviceFingerprint {

    @Volatile
    private var cached: String? = null

    @JvmStatic
    fun get(): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val id = runCatching {
                Settings.Secure.getString(
                    GlobalAppContext.get().contentResolver,
                    Settings.Secure.ANDROID_ID,
                )
            }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: Build.MODEL?.takeIf { it.isNotEmpty() }
                ?: "unknown"
            cached = id
            return id
        }
    }
}
