package org.autojs.autojs.mgmt.client.engine.shell

import android.util.Log
import org.autojs.autojs.mgmt.client.engine.EngineProcessHandle
import org.autojs.autojs.mgmt.client.engine.ShellCommandRunner
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * su 命令执行器（SCRCPY_ROOT 档）。
 *
 * @description 以 `su -c` 在设备本地启动/终止 scrcpy-server。复用调用方缓存的
 * root 探测结果：isAvailable 仅判断 su 短命令是否 exit 0（首次构造时执行一次）。
 */
class SuCommandRunner : ShellCommandRunner {

    /** waitFor 与超时 kill 需要并发（与 ManagementPlatformClient.rootExecutor 同理） */
    private val waitPool = Executors.newCachedThreadPool { r ->
        Thread(r, "mgmt-scrcpy-su-wait").apply { isDaemon = true }
    }

    @Volatile
    private var suAvailable: Boolean? = null

    override fun isAvailable(): Boolean {
        suAvailable?.let { return it }
        val ok = execShort("id", 6000L) == 0
        suAvailable = ok
        return ok
    }

    override fun launch(commandLine: String): EngineProcessHandle? {
        if (!isAvailable()) return null
        return try {
            // server 长驻：su 进程本身即句柄（app_process 为其子进程，destroy 时整组终止）
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", commandLine))
            SuProcessHandle(process)
        } catch (e: Exception) {
            Log.w(TAG, "launch via su failed", e)
            null
        }
    }

    override fun execShort(command: String, timeoutMs: Long): Int {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val future = waitPool.submit(Callable { process.waitFor() })
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                future.cancel(true)
                runCatching { process.destroy() }
                Log.w(TAG, "su execShort timeout: $command")
                -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "su execShort failed: $command", e)
            -1
        }
    }

    /**
     * su 进程句柄：pid 未知（su -c 不回传 pid）；destroy 先终止 su，
     * 存活校验另起一条 su 探测——STOP 流程由引擎再以 pkill 兜底。
     */
    private class SuProcessHandle(private val process: Process) : EngineProcessHandle {
        override val pid: Int = -1

        override val isAlive: Boolean
            get() = try {
                process.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }

        override fun destroy() {
            runCatching { process.destroy() }
        }
    }

    companion object {
        private const val TAG = "SuCommandRunner"
    }
}
