package org.autojs.autojs.mgmt.client.engine

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.autojs.autojs.mgmt.client.engine.shell.ShizukuCommandRunner
import org.autojs.autojs.mgmt.client.engine.shell.SuCommandRunner
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** SCRCPY_START 参数（已由客户端处理层填好默认值） */
data class ScrcpyStartArgs(
    val runMode: String,
    val serverVersion: String,
    val serverSha256: String?,
    val bitrate: Int,
    val fps: Int,
    val maxWidth: Int,
    val maxHeight: Int,
)

/** 引擎启动结果（外部 START 经 host 回报 COMMAND_RESULT） */
data class ScrcpyStartResult(val success: Boolean, val message: String?)

/**
 * 引擎宿主能力：由 ManagementPlatformClient 实现，引擎不直接触碰 WS/偏好。
 */
interface ScrcpyEngineHost {
    val engineContext: Context
    val engineHttpClient: OkHttpClient

    /** 平台 HTTP 基址（已把 ws/wss 换算为 http/https，无尾斜杠） */
    val platformHttpBase: String

    /** 设备接入码（matchCode） */
    val accessCode: String

    /** 发送 24B 二进制视频帧（0x10 config / 0x11 frame） */
    fun sendVideoFrame(msgType: Int, flags: Int, width: Int, height: Int, payload: ByteArray): Boolean

    /** 外部 START 最终结果 */
    fun onStartResult(result: ScrcpyStartResult)

    /** 外部 STOP 最终结果 */
    fun onStopResult(success: Boolean)

    /** 内部自恢复全部失败：上行 ENGINE_DIED */
    fun onEngineDied(message: String)
}

/**
 * 本地 scrcpy 引擎（root / Shizuku 两档共用，方案 §4.3/§4.4）。
 *
 * @description 资产准备（HTTP 下载 + SHA-256 校验）→ 特权 shell 启动 server →
 * 连接本地 abstract socket（video + control）→ 拆 scrcpy 视频包转 24B 帧上报；
 * TOUCH/KEY 经控制通道注入（真多点、低延迟）；reader 异常或看门狗无帧时重启一次，
 * 再失败上报 ENGINE_DIED。所有生命周期动作在单线程 executor 上串行。
 */
class ScrcpyLocalEngine(private val host: ScrcpyEngineHost) {

    // ===== 24B 帧消息类型（与服务端 binary-frame 对齐） =====
    private val msgH264Config = 0x10
    private val msgH264Frame = 0x11
    private val frameFlagKey = 0x01

    // ===== scrcpy v2.4 视频包标志位（long，大端读取） =====
    private val packetFlagConfig = 1L shl 63
    private val packetFlagKeyFrame = 1L shl 62

    /** 引擎生命周期单线程（启动/停止/重启/看门狗判定全部串行） */
    private val lifecycle: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mgmt-scrcpy-engine").apply { isDaemon = true }
        }

    /** 长按/滑动持时专用单线程（不阻塞控制写入与生命周期） */
    private val gestureExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mgmt-scrcpy-gesture").apply { isDaemon = true }
    }

    private val random = SecureRandom()

    // ===== 引擎状态（全部在生命周期线程读写；控制通道写锁独立） =====
    @Volatile private var started = false
    private var stopping = false
    private var runner: ShellCommandRunner? = null
    private var processHandle: EngineProcessHandle? = null
    private var videoSocket: LocalSocket? = null
    private var controlSocket: LocalSocket? = null
    private var videoInput: DataInputStream? = null
    private var readerThread: Thread? = null

    /** 最近参数（重启用） */
    private var currentArgs: ScrcpyStartArgs? = null

    /** 看门狗已尝试重启（每次成功启动后清零） */
    private var restartAttempted = false

    /** 当前看门狗定时任务（每次重启先取消旧任务，防止任务堆积重复触发） */
    private var watchdogTask: ScheduledFuture<*>? = null

    /** 最近一帧时间（reader 写、看门狗读） */
    @Volatile private var lastFrameAt = 0L

    /** 视频尺寸（codec meta；控制消息定位用） */
    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0

    private val controlLock = Any()

    fun isActive(): Boolean = started

    /** 外部 START：异步生命周期，结果经 host 回报 */
    fun start(args: ScrcpyStartArgs) {
        lifecycle.execute { doStart(args, internalRestart = false) }
    }

    /** 外部 STOP */
    fun stop() {
        lifecycle.execute {
            stopInternal("外部 STOP")
            host.onStopResult(true)
        }
    }

    /** 平台连接丢失/登出：静默停止，不回报 STOP 结果 */
    fun abort() {
        lifecycle.execute { runCatching { stopInternal("平台连接丢失") } }
    }

    /**
     * 注入高层手势（tap / long_press / swipe）。坐标必须已是真实屏幕坐标
     * （调用方已按截图/屏幕尺寸完成映射）。仅在引擎活跃时动作，否则返回 false 走旧通道。
     */
    fun injectGesture(
        type: String,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
    ): Boolean {
        if (!started) return false
        val width = videoWidth
        val height = videoHeight
        if (width <= 0 || height <= 0) return false
        return when (type) {
            "long_press" -> {
                val hold = durationMs.takeIf { it > 0 } ?: 700L
                writeTouch(ScrcpyControlEncoder.ACTION_DOWN, startX, startY, width, height)
                gestureExecutor.execute {
                    try {
                        Thread.sleep(hold.coerceAtMost(3000L))
                    } catch (_: InterruptedException) {
                        return@execute
                    }
                    writeTouch(ScrcpyControlEncoder.ACTION_UP, startX, startY, width, height)
                }
                true
            }
            "swipe" -> {
                val duration = durationMs.takeIf { it > 0 } ?: 300L
                writeTouch(ScrcpyControlEncoder.ACTION_DOWN, startX, startY, width, height)
                gestureExecutor.execute {
                    val holdMs = duration.coerceAtMost(2000L).coerceAtLeast(50L)
                    try {
                        // 多段 MOVE 插值（与服务端 ADB 会话一致）：路径与节奏更贴近真实滑动
                        for (i in 1..SWIPE_STEPS) {
                            val ratio = i.toDouble() / SWIPE_STEPS
                            val px = (startX + (endX - startX) * ratio).toInt()
                            val py = (startY + (endY - startY) * ratio).toInt()
                            writeTouch(ScrcpyControlEncoder.ACTION_MOVE, px, py, width, height)
                            Thread.sleep(holdMs / SWIPE_STEPS)
                        }
                        writeTouch(ScrcpyControlEncoder.ACTION_UP, endX, endY, width, height)
                    } catch (_: InterruptedException) {
                        // 中断即放弃
                    }
                }
                true
            }
            else -> {
                // tap
                writeTouch(ScrcpyControlEncoder.ACTION_DOWN, startX, startY, width, height)
                writeTouch(ScrcpyControlEncoder.ACTION_UP, startX, startY, width, height)
                true
            }
        }
    }

    /**
     * 注入系统按键（back/home/recents/notifications/collapse）。
     * @returns true=已写控制通道
     */
    fun pressKey(action: String): Boolean {
        if (!started) return false
        return when (action) {
            "back" -> writeControl(ScrcpyControlEncoder.backOrScreenOn(ScrcpyControlEncoder.ACTION_DOWN))
            "home" ->
                writeControl(ScrcpyControlEncoder.keycode(ScrcpyControlEncoder.ACTION_DOWN, ScrcpyControlEncoder.KEYCODE_HOME)) &&
                    writeControl(ScrcpyControlEncoder.keycode(ScrcpyControlEncoder.ACTION_UP, ScrcpyControlEncoder.KEYCODE_HOME))
            "recents" ->
                writeControl(ScrcpyControlEncoder.keycode(ScrcpyControlEncoder.ACTION_DOWN, ScrcpyControlEncoder.KEYCODE_APP_SWITCH)) &&
                    writeControl(ScrcpyControlEncoder.keycode(ScrcpyControlEncoder.ACTION_UP, ScrcpyControlEncoder.KEYCODE_APP_SWITCH))
            "notifications" -> writeControl(ScrcpyControlEncoder.expandNotificationPanel())
            "collapse" -> writeControl(ScrcpyControlEncoder.collapsePanel())
            else -> false
        }
    }

    // ========== 内部：启动流程 ==========

    private fun doStart(args: ScrcpyStartArgs, internalRestart: Boolean) {
        try {
            if (started) {
                stopInternal("重复 START / 内部重启")
            }
            currentArgs = args
            stopping = false
            val commandRunner = createRunner(args.runMode)
            runner = commandRunner
            if (!commandRunner.isAvailable()) {
                finishStartFailure(internalRestart, "特权身份不可用（root 或 Shizuku 未就绪）")
                return
            }

            val jarFile = ensureServerJar(args)

            val scid = String.format("%08x", random.nextInt())
            val commandLine = buildCommandLine(jarFile, scid, args)
            val handle = commandRunner.launch(commandLine)
            processHandle = handle
            if (handle == null) {
                finishStartFailure(internalRestart, "scrcpy-server 启动失败（特权 shell 拒绝执行）")
                return
            }
            // 给 server 一点崩溃暴露时间（参数错误会立刻退出）
            Thread.sleep(250)
            if (!handle.isAlive) {
                finishStartFailure(internalRestart, "scrcpy-server 启动后立即退出（参数或版本不匹配）")
                return
            }

            connectEngineSockets(scid)
            startReader()
            scheduleWatchdog()
            started = true
            restartAttempted = false
            lastFrameAt = System.currentTimeMillis()
            Log.i(TAG, "scrcpy engine started mode=${args.runMode} scid=$scid ${videoWidth}x${videoHeight}")
            if (!internalRestart) {
                host.onStartResult(ScrcpyStartResult(true, null))
            }
        } catch (e: Exception) {
            Log.w(TAG, "doStart failed", e)
            runCatching { stopInternal("启动失败清理") }
            finishStartFailure(internalRestart, e.message ?: "scrcpy 引擎启动异常")
        }
    }

    private fun finishStartFailure(internalRestart: Boolean, message: String) {
        started = false
        if (internalRestart) {
            host.onEngineDied(message)
        } else {
            host.onStartResult(ScrcpyStartResult(false, message))
        }
    }

    private fun createRunner(runMode: String): ShellCommandRunner =
        if (runMode == "shizuku") ShizukuCommandRunner() else SuCommandRunner()

    /**
     * 连接 engine socket（顺序必须与 scrcpy DesktopConnection 一致）：
     * video（abstract scrcpy_<scid>）→ 读 dummy byte + 12B codec meta →
     * control → 读 dummy byte。
     */
    private fun connectEngineSockets(scid: String) {
        val socketName = "scrcpy_$scid"

        // 阶段 A：连接 video（server LocalServerSocket 绑定后即可连，重试到 8s）
        val video = LocalSocket()
        val connected = retryUntil(8000L) {
            video.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT), 2000)
        }
        if (!connected) {
            runCatching { video.close() }
            throw IOException("无法连接 scrcpy video socket：$socketName")
        }
        video.soTimeout = 8000
        val input = DataInputStream(BufferedInputStream(video.inputStream, 64 * 1024))

        // 阶段 B：dummy byte + codec meta（12B：codecId int + width int + height int）
        val dummy = ByteArray(1)
        input.readFully(dummy)
        val meta = ByteArray(12)
        input.readFully(meta)
        val metaBuf = ByteBuffer.wrap(meta).order(ByteOrder.BIG_ENDIAN)
        metaBuf.int // codecId（H264，忽略）
        videoWidth = metaBuf.int
        videoHeight = metaBuf.int

        // 阶段 C：control 连接（server 已在 accept control，短重试）
        val control = LocalSocket()
        val controlConnected = retryUntil(5000L) {
            control.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT), 2000)
        }
        if (!controlConnected) {
            runCatching { control.close() }
            throw IOException("无法连接 scrcpy control socket：$socketName")
        }
        control.soTimeout = 0
        val controlIn = DataInputStream(control.inputStream)
        val controlDummy = ByteArray(1)
        controlIn.readFully(controlDummy)

        videoSocket = video
        controlSocket = control
        videoInput = input
    }

    // ========== 内部：视频读取与看门狗 ==========

    private fun startReader() {
        val input = videoInput ?: return
        val thread = Thread({
            Log.i(TAG, "scrcpy reader started")
            try {
                while (!stopping) {
                    val header = ByteArray(12)
                    input.readFully(header)
                    val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                    val ptsAndFlags = buf.long
                    val packetSize = buf.int
                    if (packetSize < 0 || packetSize > MAX_PACKET_SIZE) {
                        throw IOException("非法 scrcpy 包长度：$packetSize")
                    }
                    val data = ByteArray(packetSize)
                    input.readFully(data)
                    lastFrameAt = System.currentTimeMillis()
                    if (ptsAndFlags and packetFlagConfig != 0L) {
                        host.sendVideoFrame(msgH264Config, 0, videoWidth, videoHeight, data)
                    } else {
                        val flags = if (ptsAndFlags and packetFlagKeyFrame != 0L) frameFlagKey else 0
                        host.sendVideoFrame(msgH264Frame, flags, videoWidth, videoHeight, data)
                    }
                }
            } catch (e: EOFException) {
                handleReaderCrash(e)
            } catch (e: IOException) {
                handleReaderCrash(e)
            }
        }, "mgmt-scrcpy-reader")
        thread.isDaemon = true
        readerThread = thread
        thread.start()
    }

    private fun handleReaderCrash(e: Exception) {
        if (stopping) {
            return
        }
        Log.w(TAG, "scrcpy reader crashed", e)
        lifecycle.execute { attemptRestart("视频连接中断：${e.message}") }
    }

    /** 看门狗：无帧超过阈值 → 重启一次；再次无帧 → ENGINE_DIED */
    private fun scheduleWatchdog() {
        watchdogTask?.cancel(false)
        val checkInterval = WATCHDOG_MS / 2
        watchdogTask =
            lifecycle.scheduleAtFixedRate(
                {
                    runCatching {
                        if (started && System.currentTimeMillis() - lastFrameAt > WATCHDOG_MS) {
                            attemptRestart("引擎看门狗：超过 ${WATCHDOG_MS}ms 无视频帧")
                        }
                    }
                },
                checkInterval,
                checkInterval,
                TimeUnit.MILLISECONDS,
            )
    }

    private fun attemptRestart(reason: String) {
        Log.w(TAG, "attemptRestart: $reason")
        if (restartAttempted) {
            runCatching { stopInternal("自恢复失败放弃") }
            host.onEngineDied(reason)
            return
        }
        restartAttempted = true
        val args = currentArgs
        if (args == null) {
            host.onEngineDied(reason)
            return
        }
        stopInternal("看门狗重启")
        doStart(args, internalRestart = true)
    }

    // ========== 内部：停止 ==========

    /**
     * 停止引擎并清理全部资源（同步，调用方在生命周期线程）。
     */
    private fun stopInternal(reason: String) {
        stopping = true
        Log.i(TAG, "stopping scrcpy engine: $reason")
        watchdogTask?.let { task ->
            task.cancel(false)
            watchdogTask = null
        }
        runCatching { controlSocket?.close() }
        runCatching { videoSocket?.close() }
        readerThread?.let { thread ->
            try {
                thread.join(1500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val handle = processHandle
        val commandRunner = runner
        if (handle != null && commandRunner != null) {
            runCatching { handle.destroy() }
            Thread.sleep(200)
            if (handle.isAlive) {
                // pkill 兜底：按 server 启动类匹配（同设备同时刻只有一个引擎）
                commandRunner.execShort("pkill -f com.genymobile.scrcpy.Server", 4000L)
                Thread.sleep(200)
                if (handle.isAlive && handle.pid > 0) {
                    commandRunner.execShort("kill -9 ${handle.pid} 2>/dev/null", 4000L)
                }
            }
        }
        started = false
        stopping = false
        processHandle = null
        videoSocket = null
        controlSocket = null
        videoInput = null
        readerThread = null
    }

    // ========== 内部：资产准备 ==========

    /**
     * 确保 scrcpy-server jar 就绪：已存在且 SHA-256 匹配则直接使用，否则 HTTP 下载。
     */
    private fun ensureServerJar(args: ScrcpyStartArgs): File {
        val dir = File(host.engineContext.filesDir, "mgmt/scrcpy")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val fileName = "scrcpy-server-v${args.serverVersion}"
        val target = File(dir, fileName)
        if (target.isFile && matchesSha256(target, args.serverSha256)) {
            return target
        }
        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8")
        val encodedCode = java.net.URLEncoder.encode(host.accessCode, "UTF-8")
        val url = "${host.platformHttpBase}/api/device/engine/files/$encodedName?matchCode=$encodedCode"
        Log.i(TAG, "downloading scrcpy server: $url")
        val request = Request.Builder().url(url).build()
        host.engineHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("scrcpy-server 下载失败：HTTP ${response.code}")
            }
            val bytes = response.body?.bytes()
            if (bytes == null || bytes.isEmpty()) {
                throw IOException("scrcpy-server 下载内容为空")
            }
            if (args.serverSha256 != null && sha256Hex(bytes) != args.serverSha256.lowercase()) {
                throw IOException("scrcpy-server 校验失败：SHA-256 不匹配")
            }
            val tmp = File(dir, "$fileName.tmp")
            tmp.writeBytes(bytes)
            if (target.exists()) {
                target.delete()
            }
            if (!tmp.renameTo(target)) {
                throw IOException("scrcpy-server 落盘失败")
            }
        }
        return target
    }

    private fun matchesSha256(file: File, expected: String?): Boolean {
        if (expected == null) {
            // 服务端未给摘要（旧版）：本地文件存在即信任
            return file.isFile && file.length() > MIN_SERVER_FILE_SIZE
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex() == expected.lowercase()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    // ========== 内部：控制写入 ==========

    private fun writeTouch(action: Byte, x: Int, y: Int, width: Int, height: Int): Boolean {
        val data = ScrcpyControlEncoder.touchEvent(action, VIRTUAL_POINTER_ID, x, y, width, height)
        return writeControl(data)
    }

    private fun writeControl(data: ByteArray): Boolean {
        val socket = controlSocket ?: return false
        synchronized(controlLock) {
            return try {
                socket.outputStream.apply {
                    write(data)
                    flush()
                }
                true
            } catch (e: IOException) {
                Log.w(TAG, "writeControl failed", e)
                false
            }
        }
    }

    // ========== 内部：命令行 ==========

    /**
     * 拼装 scrcpy v2.4 启动命令（参数取证自 scrcpy v2.4 Options.java；
     * args[0] 必须是与 jar 严格匹配的版本字符串）。
     */
    private fun buildCommandLine(jar: File, scid: String, args: ScrcpyStartArgs): String {
        val parts = mutableListOf(
            "CLASSPATH=${jar.absolutePath}",
            "app_process",
            "/",
            "com.genymobile.scrcpy.Server",
            args.serverVersion,
            "scid=$scid",
            "log_level=warn",
            "video=true",
            "audio=false",
            "video_codec=h264",
            "video_bit_rate=${args.bitrate}",
            "max_fps=${args.fps}",
        )
        computeMaxSize(args)?.let { parts.add("max_size=$it") }
        parts += listOf(
            "tunnel_forward=true",
            "control=true",
            "send_device_meta=false",
            "send_frame_meta=true",
            "send_dummy_byte=true",
            "send_codec_meta=true",
            "raw_stream=false",
            "cleanup=false",
            "power_on=true",
            "stay_awake=false",
            "show_touches=false",
        )
        return parts.joinToString(" ")
    }

    /** max_size 同时约束宽高（保持比例），取用户宽/高上限中更小者 */
    private fun computeMaxSize(args: ScrcpyStartArgs): Int? {
        val candidates = listOf(args.maxWidth, args.maxHeight).filter { it > 0 }
        return candidates.minOrNull()
    }

    /** 重试执行动作直到成功或超时；动作异常视为本轮失败 */
    private fun retryUntil(timeoutMs: Long, action: () -> Unit): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                action()
                return true
            } catch (e: Exception) {
                lastError = e
                try {
                    Thread.sleep(RETRY_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        if (lastError != null) {
            Log.d(TAG, "retryUntil exhausted: ${lastError.message}")
        }
        return false
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "ScrcpyLocalEngine"

        /** 虚拟手指 pointerId（DOWN→UP 一致即可，server 不校验具体值） */
        private const val VIRTUAL_POINTER_ID = 0L

        /** 无帧看门狗阈值（与服务端 engine.watchdogMs 对齐：10s） */
        private const val WATCHDOG_MS = 10_000L

        /** 滑动 MOVE 插值段数（与服务端 ADB 会话一致：10 段） */
        private const val SWIPE_STEPS = 10

        private const val RETRY_INTERVAL_MS = 300L

        /** 单包长度上限（64MB，异常包判定为协议失步） */
        private const val MAX_PACKET_SIZE = 64 * 1024 * 1024

        /** 未提供摘要时本地资产最小可信体积 */
        private const val MIN_SERVER_FILE_SIZE = 10_000L
    }
}
