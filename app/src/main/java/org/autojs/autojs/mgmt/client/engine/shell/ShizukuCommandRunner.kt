package org.autojs.autojs.mgmt.client.engine.shell

import android.util.Log
import org.autojs.autojs.mgmt.client.engine.EngineProcessHandle
import org.autojs.autojs.mgmt.client.engine.ShellCommandRunner
import org.autojs.autojs.runtime.api.WrappedShizuku

/**
 * Shizuku 命令执行器（SCRCPY_SHIZUKU 档）。
 *
 * @description 复用 AutoJs6 官方自带的 Shizuku 依赖与 [WrappedShizuku] 封装，
 * 经 UserService（shell uid 2000）执行命令；server 以后台任务方式启动并回传 pid。
 * 不新增任何第三方依赖（方案 §4.4）。
 */
class ShizukuCommandRunner : ShellCommandRunner {

    override fun isAvailable(): Boolean = runCatching { WrappedShizuku.isOperational() }.getOrDefault(false)

    override fun launch(commandLine: String): EngineProcessHandle? {
        if (!isAvailable()) return null
        // server 后台化：stdout/stderr 必须重定向，否则后台进程持有管道、
        // 后续命令的输出会与之串扰；echo 回传 pid 供精确 kill。
        val shellScript = "$commandLine >/dev/null 2>&1 & echo $PID_MARKER$!"
        val result = runCatching { WrappedShizuku.execCommand(shellScript) }.getOrNull() ?: return null
        if (result.code != 0) {
            Log.w(TAG, "shizuku launch rejected: ${result.error}")
            return null
        }
        val pid = PID_REGEX.find(result.result)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        if (pid <= 0) {
            Log.w(TAG, "shizuku launch: pid not parsed from ${result.result}")
            return null
        }
        return ShizukuProcessHandle(pid, this)
    }

    override fun execShort(command: String, timeoutMs: Long): Int {
        if (!isAvailable()) return -1
        return try {
            val result = WrappedShizuku.execCommand(command)
            result.code
        } catch (e: Exception) {
            Log.w(TAG, "shizuku execShort failed: $command", e)
            -1
        }
    }

    private class ShizukuProcessHandle(
        override val pid: Int,
        private val runner: ShizukuCommandRunner,
    ) : EngineProcessHandle {

        override val isAlive: Boolean
            get() = runner.execShort("kill -0 $pid 2>/dev/null", 4000L) == 0

        override fun destroy() {
            runner.execShort("kill $pid 2>/dev/null", 4000L)
        }
    }

    companion object {
        private const val TAG = "ShizukuCommandRunner"
        private const val PID_MARKER = "SCRCPY_PID:"
        private val PID_REGEX = Regex("$PID_MARKER(\\d+)")
    }
}
