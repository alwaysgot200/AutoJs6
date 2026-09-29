package org.autojs.autojs.runtime.api

import android.Manifest
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.autojs.autojs.permission.PostNotificationsPermission
import org.autojs.autojs.util.IntentUtils.startSafely
import org.autojs.autojs.util.RomUtils
import java.util.WeakHashMap

class Permissions(private val context: Context) {

    var postNotifications: IPermissionToggleable? = null
    var notificationAccess: IPermissionToggleable? = null
    var usageStatesAccess: IPermissionToggleable? = null
    var ignoreBatteryOptimizations: IPermissionToggleable? = null
    var backgroundStart = object : IPermissionToggleable {
        override val description = "后台弹出界面 / Start in background"
        override fun has() = RomUtils.isBackgroundStartGranted(context)
        override fun config() {
            when {
                RomUtils.isMiui() -> {
                    Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                        setClassName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.permissions.PermissionsEditorActivity",
                        )
                        putExtra("extra_pkgname", context.packageName)
                    }.startSafely(context)
                }
                else -> super.config()
            }
        }
    }
    var displayOverOtherApps: IPermissionToggleable? = null
    var writeSystemSettings: IPermissionToggleable? = null
    var writeSecuritySettings: IPermissionToggleable? = null
    var projectMediaAccess: IPermissionToggleable? = null
    val isBackgroundStartGranted: Boolean
        get() = RomUtils.isBackgroundStartGranted(context)

    // TODO by SuperMonster003 on Aug 28, 2023.

    /* permissions.postNotifications */
    /* permissions.notificationAccess */
    /* permissions.usageStatesAccess */
    /* permissions.ignoreBatteryOptimizations */
    /* permissions.backgroundStart */
    /* permissions.displayOverOtherApps */
    /* permissions.writeSystemSettings */
    /* permissions.writeSecuritySettings */
    /* permissions.projectMediaAccess */

    companion object {

        private val TAG = Permissions::class.java.simpleName

        /**
         * Cache activity result launchers per activity instance.
         * zh-CN: 按 Activity 实例缓存 ActivityResultLauncher, 避免在 RESUMED 时重复 register 导致崩溃.
         *
         * vendor:fix (LeakCanary 实证, 2026-09-29 二次修正):
         * 上游原值是 `WeakHashMap<Activity, Launcher>` 强 value。launcher 单向强引用
         * ActivityResultRegistry → Activity (value→key 强链), 条目永不清除,
         * Activity onDestroy 后整实例被静态缓存泄漏。
         *
         * 关键事实 (对 androidx.activity 1.12.2 字节码核实): ActivityResultRegistry
         * 的字段表不含 launcher——register() 创建 launcher 后直接返回, 从不回存,
         * 即 registry 不持有 launcher; 而 MainActivity/SettingsActivity 的 onCreate
         * 又丢弃了 registerForActivityResult 的返回值。因此 value 必须保持强引用,
         * 绝不能包 WeakReference (上一版修复包成 WeakReference 后, launcher 随时可被
         * GC, 通知权限申请会走到 RESUMED 态注册分支直接抛 IllegalStateException)。
         *
         * 正确防泄漏方式: ON_DESTROY 观察者显式移除条目, 断开 launcher→registry→
         * Activity 静态强链; 观察者本身随 Activity 的 LifecycleRegistry 一同销毁。
         */
        private val requestMultiplePermissionsLauncherCache =
            WeakHashMap<FragmentActivity, ActivityResultLauncher<Array<String>>>()

        /**
         * Register the launcher early (e.g. in Activity.onCreate()).
         * zh-CN: 请尽早注册 (例如在 Activity.onCreate() 内), 否则在 STARTED/RESUMED 状态 register 会触发 IllegalStateException.
         */
        @JvmStatic
        fun registerRequestMultiplePermissionsLauncher(activity: FragmentActivity): ActivityResultLauncher<Array<String>> {
            getRegisteredRequestMultiplePermissionsLauncher(activity)?.let { return it }
            return activity.registerForActivityResult(RequestMultiplePermissions()) { resultMap ->
                for ((key: String, isGranted: Boolean) in resultMap) {
                    Log.d(TAG, "$key: $isGranted")
                    when (key) {
                        Manifest.permission.POST_NOTIFICATIONS -> {
                            // Deliver one-shot callback.
                            // zh-CN: 触发一次性回调 (如果存在), 然后移除, 避免重复触发和泄漏.
                            PostNotificationsPermission.postNotificationsResultCallbackCache.remove(activity)?.invoke(isGranted)
                            break
                        }
                    }
                }
            }.also { launcher ->
                requestMultiplePermissionsLauncherCache[activity] = launcher
                // vendor:fix: 静态缓存的 value 是 launcher 存活的唯一引用, 不能弱引用;
                // 改在 ON_DESTROY 显式移除条目, 消除 Activity 泄漏 (观察者随 LifecycleRegistry 销毁)。
                activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onDestroy(owner: LifecycleOwner) {
                        requestMultiplePermissionsLauncherCache.remove(activity)
                    }
                })
            }
        }

        /**
         * Get a previously registered launcher.
         * zh-CN: 获取已注册的 launcher; 若未注册则返回 null.
         */
        @JvmStatic
        fun getRegisteredRequestMultiplePermissionsLauncher(activity: FragmentActivity): ActivityResultLauncher<Array<String>>? {
            return requestMultiplePermissionsLauncherCache[activity]
        }

        @JvmStatic
        fun getRequestMultiplePermissionsLauncher(activity: FragmentActivity): ActivityResultLauncher<Array<String>> {
            getRegisteredRequestMultiplePermissionsLauncher(activity)?.let { return it }

            // Fail fast with a clearer message when called too late.
            // zh-CN: 如果调用时机过晚, 直接给出更明确的错误提示, 避免原始栈信息难以定位.
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                throw IllegalStateException(
                    "RequestMultiplePermissions launcher is not registered yet, and cannot be registered when state is " +
                            "${activity.lifecycle.currentState}. Register it in onCreate() via " +
                            "Permissions.registerRequestMultiplePermissionsLauncher(activity).",
                )
            }

            // Register now (only safe before STARTED).
            // zh-CN: 仅在 STARTED 之前注册才是安全的.
            return registerRequestMultiplePermissionsLauncher(activity)
        }

    }

}