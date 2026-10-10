package org.autojs.autojs.mgmt.client

import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * JPEG 流式自推调度器（mgmt 自有，无第三方依赖）
 *
 * @description 对应方案 §4.1A：观看期间设备自步进连续抓帧并直接推送，
 * 帧间不等待浏览器请求，消除跨境链路下每帧一个完整往返 RTT 的停顿。
 *
 * 线程模型：全部状态只在调度线程（mgmt-screen-stream）读写；外部 start/stop/reportResult
 * 一律经 Handler post 切入，避免跨线程数据竞争。
 *
 * 节奏自适应：
 * - 初始间隔 80ms；
 * - 系统截图限流（AccessibilityService errorCode=3）或其他失败 → 间隔 +100ms 退避，
 *   学习本机允许的最小抓帧间隔；
 * - 连续成功 20 帧 → 间隔 -20ms 下探（下限 33ms ≈ 30fps）。
 *
 * in-flight 始终单帧，并带看门狗：抓帧回调永久缺失时按失败解锁，避免流永久停摆。
 * START 重复发送 = 参数热更新（幂等），STOP 立即停摆。
 */
class ScreenStreamer(private val captureRequester: CaptureRequester) {

    interface CaptureRequester {
        /**
         * 请求抓一帧；抓取结果经由 [reportResult] 回报。
         *
         * @returns false = 抓帧未能发起（当前无截图通道），按失败进入退避
         */
        fun requestCapture(quality: Int, maxWidth: Int, maxHeight: Int): Boolean

        /**
         * 当前是否存在可用抓帧通道（无障碍 Android 11+ 或 root）；
         * START 前置检查用，不满足时服务端启动失败、会话不登记。
         */
        fun canCaptureNow(): Boolean
    }

    enum class CaptureOutcome { SUCCESS, RATE_LIMITED, FAILED }

    private val thread = HandlerThread("mgmt-screen-stream").apply { isDaemon = true }
    private val handler: Handler by lazy {
        thread.start()
        Handler(thread.looper)
    }

    /** 推流总开关：仅调度线程写；外部经 isActive 读（volatile 保证可见性） */
    @Volatile
    private var active = false

    private var intervalMs = INITIAL_INTERVAL_MS
    private var consecutiveSuccess = 0
    private var captureInFlight = false

    private var quality = DEFAULT_QUALITY
    private var maxWidth = 0
    private var maxHeight = 0

    fun isActive(): Boolean = active

    /**
     * 启动连续推流；已在推流中则仅热更新参数（下一帧生效，不打断当前节奏）。
     *
     * @param onStarted 启动结果回调（调度线程）：true=已进入推流态/热更新完成；
     *                  false=当前无抓帧通道，未启动（调用方据此回 COMMAND_RESULT 失败）
     */
    fun start(
        newQuality: Int,
        newMaxWidth: Int,
        newMaxHeight: Int,
        onStarted: (success: Boolean) -> Unit,
    ) {
        handler.post {
            quality = newQuality
            maxWidth = newMaxWidth
            maxHeight = newMaxHeight
            if (active) {
                // 热更新：参数下一帧生效，节奏不中断
                onStarted(true)
                return@post
            }
            if (!captureRequester.canCaptureNow()) {
                resetState()
                onStarted(false)
                return@post
            }
            active = true
            intervalMs = INITIAL_INTERVAL_MS
            consecutiveSuccess = 0
            captureInFlight = false
            handler.removeCallbacksAndMessages(null)
            handler.post(dispatchCaptureRunnable)
            onStarted(true)
        }
    }

    /**
     * 停止连续推流并取消所有待调度帧/看门狗（已发起的抓帧回调结果将被丢弃）
     */
    fun stop() {
        handler.post {
            if (!active) {
                return@post
            }
            active = false
            handler.removeCallbacksAndMessages(null)
            resetState()
        }
    }

    /**
     * 抓帧结果回报（由截图回调线程/root 线程调用，统一 post 到调度线程处理）
     */
    fun reportResult(outcome: CaptureOutcome) {
        handler.post {
            handleOutcome(outcome)
        }
    }

    private fun handleOutcome(outcome: CaptureOutcome) {
        if (!active) {
            return
        }
        handler.removeCallbacks(watchdogRunnable)
        captureInFlight = false
        when (outcome) {
            CaptureOutcome.SUCCESS -> {
                consecutiveSuccess += 1
                if (consecutiveSuccess >= PROBE_AFTER_SUCCESSES && intervalMs > MIN_INTERVAL_MS) {
                    intervalMs = maxOf(MIN_INTERVAL_MS, intervalMs - PROBE_STEP_MS)
                    consecutiveSuccess = 0
                }
            }
            // 限流与其他失败同策略：阶梯退避，成功后重新下探
            CaptureOutcome.RATE_LIMITED, CaptureOutcome.FAILED -> {
                consecutiveSuccess = 0
                intervalMs = minOf(MAX_INTERVAL_MS, intervalMs + BACKOFF_STEP_MS)
            }
        }
        handler.postDelayed(dispatchCaptureRunnable, intervalMs)
    }

    private val dispatchCaptureRunnable = Runnable { dispatchCapture() }

    /** 抓帧看门狗：回调超时未到达时按失败解锁 in-flight，防止流永久冻结 */
    private val watchdogRunnable = Runnable {
        Log.w(TAG, "capture callback timed out after ${CAPTURE_WATCHDOG_MS}ms")
        handleOutcome(CaptureOutcome.FAILED)
    }

    private fun dispatchCapture() {
        if (!active || captureInFlight) {
            return
        }
        captureInFlight = true
        val dispatched = try {
            captureRequester.requestCapture(quality, maxWidth, maxHeight)
        } catch (e: Exception) {
            Log.w(TAG, "capture request threw", e)
            false
        }
        if (!dispatched) {
            handleOutcome(CaptureOutcome.FAILED)
            return
        }
        handler.postDelayed(watchdogRunnable, CAPTURE_WATCHDOG_MS)
    }

    private fun resetState() {
        intervalMs = INITIAL_INTERVAL_MS
        consecutiveSuccess = 0
        captureInFlight = false
    }

    companion object {
        private const val TAG = "ScreenStreamer"

        const val DEFAULT_QUALITY = 75

        /** 初始帧间隔（约 12.5fps），经下探可提高到约 30fps */
        private const val INITIAL_INTERVAL_MS = 80L

        /** 下探下限：33ms ≈ 30fps */
        private const val MIN_INTERVAL_MS = 33L

        /** 退避上限：持续限流/失败时最多 1fps，避免空转耗电 */
        private const val MAX_INTERVAL_MS = 1000L

        /** 限流/失败退避阶梯 */
        private const val BACKOFF_STEP_MS = 100L

        /** 连续成功后的下探步长 */
        private const val PROBE_STEP_MS = 20L

        /** 连续成功多少帧后下探一次 */
        private const val PROBE_AFTER_SUCCESSES = 20

        /** 抓帧回调看门狗超时（系统 takeScreenshot 通常秒级完成） */
        private const val CAPTURE_WATCHDOG_MS = 2000L
    }
}
