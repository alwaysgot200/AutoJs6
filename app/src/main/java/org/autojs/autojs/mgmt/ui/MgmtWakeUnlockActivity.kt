package org.autojs.autojs.mgmt.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import org.autojs.autojs.mgmt.Mgmt

/**
 * MINIMAL 无障档唤醒/解锁透明 Activity（mgmt 自有文件；仅经 Manifest H1 追加声明）
 *
 * @description 在无 root / 无 ADB / 未开无障碍的设备上，仍可合法完成：
 * - [OP_WAKE]：点亮屏幕（WakeLock / root 键码均失败后的第三手段）；
 * - [OP_WAKE_UNLOCK] / [OP_UNLOCK]：亮屏并解除**非密码**锁屏
 *   （API 26+ 走 KeyguardManager.requestDismissKeyguard；更低版本用窗口 flag）；
 * - [OP_HOME]：解锁成功后回主屏。
 *
 * 版本兼容（minSdk 24）：
 * - API 27+：setShowWhenLocked / setTurnScreenOn；
 * - API 24–26：FLAG_SHOW_WHEN_LOCKED / FLAG_TURN_SCREEN_ON / FLAG_DISMISS_KEYGUARD
 *   实现同等效果，且这些版本 FLAG_TURN_SCREEN_ON 确实点亮屏幕，不存在假成功。
 *
 * 边界（Android 安全模型，不可绕过）：
 * - 密码/PIN/图案锁屏：isDeviceSecure=true，不发起 dismiss，直接回报明确失败文案；
 * - 后台 Activity 启动被 ROM 拦截：由 ManagementPlatformClient 的全屏意图通知兜底，
 *   仍失败则回报中文手动指引；
 * - dismiss 回调在部分 ROM 上可能永不触发：结果超时兜底，保证 COMMAND_RESULT 必达；
 * - 启动已被 client 超时宣告失败时（慢机迟到创建），本 Activity 静默退出，杜绝矛盾双回执。
 *
 * Activity 结束即 finishAndRemoveTask，不留任务栈；全部异常吞掉并经 Mgmt 门面回报，
 * 薄层异常绝不允许崩溃宿主。
 */
class MgmtWakeUnlockActivity : Activity() {

    private var finished = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 结果超时：dismiss 回调不来 / Activity 等待期间被系统挂起时兜底回执 */
    private val resultTimeout = Runnable {
        finishWith(commandIdFromIntent(), false, "唤醒/解锁超时未响应，请重试或手动操作")
    }
    private var timeoutArmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 亮屏 / 锁屏上显示：按系统版本选择 API，低版本用窗口 flag（同样真实生效）
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
                )
            }
        }

        val op = intent?.getStringExtra(EXTRA_OP) ?: OP_WAKE
        val commandId = intent?.getStringExtra(EXTRA_COMMAND_ID)
        val launchToken = intent?.getIntExtra(EXTRA_LAUNCH_TOKEN, 0) ?: 0

        // 通知 client Activity 已真实启动；token 已失败/未知 → 静默退出，不执行、不上报
        val accepted = runCatching { Mgmt.onWakeUnlockActivityCreated(launchToken) }.getOrDefault(false)
        if (!accepted) {
            runCatching { finishAndRemoveTask() }
            return
        }

        // 撤销全屏意图通知（本 Activity 可能由其 pendingIntent 拉起）
        runCatching { NotificationManagerCompat.from(this).cancel(FULL_SCREEN_NOTIFICATION_ID) }

        armResultTimeout(commandId)

        try {
            when (op) {
                OP_WAKE -> finishWith(commandId, true, null)
                OP_WAKE_UNLOCK, OP_UNLOCK, OP_HOME -> handleUnlock(op, commandId)
                else -> finishWith(commandId, false, "未知的唤醒操作：$op")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "wake/unlock failed: $op", e)
            finishWith(commandId, false, "唤醒/解锁执行异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun handleUnlock(op: String, commandId: String?) {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        if (!keyguardManager.isKeyguardLocked) {
            // 屏幕已解锁：直接执行后续动作
            proceedAfterUnlock(op, commandId)
            return
        }

        // 密码/PIN/图案锁屏：任何 App 都不得绕过，主动给出准确文案
        if (keyguardManager.isDeviceSecure) {
            finishWith(commandId, false, SECURE_LOCK_MESSAGE)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 非密码锁屏（上滑/无密码）：API 26+ 请求系统直接解除
            keyguardManager.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        proceedAfterUnlock(op, commandId)
                    }

                    override fun onDismissCancelled() {
                        finishWith(commandId, false, "解锁被取消，请重试或手动上滑解锁")
                    }

                    override fun onDismissError() {
                        // 部分 ROM 在密码锁屏下也走到这里，文案覆盖两种可能
                        finishWith(commandId, false, SECURE_LOCK_MESSAGE)
                    }
                },
            )
        } else {
            // API 24–25：FLAG_DISMISS_KEYGUARD 已在 onCreate 添加，非密码锁会被自动解除
            proceedAfterUnlock(op, commandId)
        }
    }

    private fun proceedAfterUnlock(op: String, commandId: String?) {
        if (op != OP_HOME) {
            finishWith(commandId, true, null)
            return
        }
        val homeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(homeIntent) }
            .onSuccess { finishWith(commandId, true, null) }
            .onFailure {
                finishWith(commandId, false, "已解锁，但回主屏失败：${it.message ?: it.javaClass.simpleName}")
            }
    }

    private fun armResultTimeout(commandId: String?) {
        if (timeoutArmed) return
        timeoutArmed = true
        mainHandler.postDelayed(resultTimeout, RESULT_TIMEOUT_MS)
    }

    private fun commandIdFromIntent(): String? = intent?.getStringExtra(EXTRA_COMMAND_ID)

    private fun finishWith(commandId: String?, success: Boolean, message: String?) {
        if (finished) return
        finished = true
        if (timeoutArmed) {
            mainHandler.removeCallbacks(resultTimeout)
        }
        runCatching { Mgmt.reportWakeUnlockResult(commandId, success, message) }
        runCatching { finishAndRemoveTask() }
    }

    companion object {
        private const val TAG = "MgmtWakeUnlock"

        const val EXTRA_OP = "mgmt_wake_op"
        const val EXTRA_COMMAND_ID = "mgmt_wake_command_id"
        const val EXTRA_LAUNCH_TOKEN = "mgmt_wake_launch_token"

        /** 仅点亮屏幕 */
        const val OP_WAKE = "wake"

        /** 点亮并解除非密码锁屏 */
        const val OP_WAKE_UNLOCK = "wake_unlock"

        /** 同 wake_unlock（语义别名） */
        const val OP_UNLOCK = "unlock"

        /** 解锁成功后回主屏 */
        const val OP_HOME = "home"

        const val FULL_SCREEN_NOTIFICATION_ID = 10703

        /** dismiss / 结果回调超时兜底 */
        private const val RESULT_TIMEOUT_MS = 3000L

        const val SECURE_LOCK_MESSAGE =
            "检测到密码锁屏（密码/PIN/图案），无法远程解锁，请手动输入密码，或先在设备上开启无障碍（或 root）后重试"
    }
}
