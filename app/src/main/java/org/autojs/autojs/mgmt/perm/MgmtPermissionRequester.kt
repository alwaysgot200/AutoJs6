package org.autojs.autojs.mgmt.perm

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build

/**
 * 管理平台薄层运行时权限申请 (F24)。
 *
 * 旧版在 MainActivity 的权限准备流程中直接申请 BLUETOOTH_CONNECT/SCAN (Android S+)。
 * 薄层化后改为 Application 级 ActivityLifecycleCallbacks, 在主界面 resume 时按需申请,
 * 从而不再修改上游 MainActivity.kt。
 */
object MgmtPermissionRequester {

    private const val MAIN_ACTIVITY_CLASS = "org.autojs.autojs.ui.main.MainActivity"
    private const val REQUEST_CODE_BLUETOOTH = 1002

    @JvmStatic
    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                if (activity.javaClass.name == MAIN_ACTIVITY_CLASS) {
                    requestBluetoothIfNeeded(activity)
                }
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun requestBluetoothIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
        val missing = permissions.filter {
            activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            activity.requestPermissions(missing.toTypedArray(), REQUEST_CODE_BLUETOOTH)
        }
    }
}
