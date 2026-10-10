package org.autojs.autojs.mgmt.client.engine

/**
 * scrcpy-server 进程启动句柄。
 *
 * @description runner 启动长驻 server 后返回；STOP 时优先按句柄精确终止，
 * 再以 pkill 兜底，确保子进程死亡（方案 §4.3 进程管理）。
 */
interface EngineProcessHandle {
    /** server 进程 pid；未知 = -1 */
    val pid: Int

    /** 进程是否存活（探测失败一律按死亡处理） */
    val isAlive: Boolean

    /** 终止进程（一次调用即返回；不保证同步死亡，调用方随后校验 isAlive） */
    fun destroy()
}

/**
 * shell 命令执行器：scrcpy-server 以特权身份在设备本地启动的唯一出口。
 *
 * - root 档：[org.autojs.autojs.mgmt.client.engine.shell.SuCommandRunner]（su -c）；
 * - Shizuku 档：[org.autojs.autojs.mgmt.client.engine.shell.ShizukuCommandRunner]
 *   （经官方自带 Shizuku UserService 执行，shell uid 2000）。
 *
 * 实现要求：所有方法必须线程封闭或内部同步（调用方为单线程引擎 + reader 线程）；
 * 任何启动/执行异常自行捕获，失败按 null / -1 / false 返回，不允许抛向引擎外。
 */
interface ShellCommandRunner {
    /** 当前特权身份是否可用（su 可达 / Shizuku 运行且已授权） */
    fun isAvailable(): Boolean

    /**
     * 启动 scrcpy-server 长驻进程（立即返回，不等待 server 退出）
     *
     * @param commandLine 完整命令行（CLASSPATH=... app_process / com.genymobile.scrcpy.Server ...）
     * @return 启动句柄；身份不可用 / 启动异常返回 null
     */
    fun launch(commandLine: String): EngineProcessHandle?

    /**
     * 执行一条短命令并等待结束（kill / pkill / 存活探测）
     *
     * @return 命令 exit code；身份不可用 / 超时 / 异常 = -1
     */
    fun execShort(command: String, timeoutMs: Long = 8000L): Int
}
