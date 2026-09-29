package org.autojs.autojs.mgmt.client

import android.util.Log
import org.autojs.autojs.app.GlobalAppContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 上行关键消息持久化发件箱（跨境弱网连接稳定性优化方案 §5.3，P1）。
 *
 * 解决问题: 断线"瞬间"发出的 SCRIPT_STATUS_UPDATE 终态 / COMMAND_RESULT 回执 /
 * LOG_LINES 日志, TCP 发送缓冲未被对端处理即丢, 服务端因此永久缺一条终态记录。
 *
 * 机制:
 * 1. 关键消息在 [ManagementPlatformClient.send] 中先经 [enqueue] 落盘（App 内部存储,
 *    单文件 JSON, 原子写）, 再走当前 WebSocket 立即发送;
 * 2. 服务端处理成功回 MESSAGE_ACK, 客户端 [acknowledge] 后删除条目;
 * 3. ACK 丢失/连接死亡: 条目留存, 下次收到 AUTH_OK 后 [flush] 按 FIFO 限速重放,
 *    服务端按 msgId 幂等去重（方案 §6.1）, 重复帧只回 ACK 不重复落库;
 * 4. 媒体帧（截图/投屏二进制）与 HEARTBEAT 不入队——前者过期即废, 后者重连只发最新;
 * 5. 容量上限 500 条 / 1 MiB, 超限按优先级淘汰（LOG_LINES < SCRIPT_STATUS_UPDATE <
 *    COMMAND_RESULT, 同级先丢最旧）; 超过 24h 或重放 20 轮仍无 ACK 的条目丢弃并告警,
 *    防止毒消息永久驻留。
 *
 * 不引入第三方依赖（无 Room）；org.json / java.io 均为公共 API；全部新代码位于 mgmt 包内。
 */
object OutboxManager {

    private const val TAG = "MgmtOutbox"

    private const val DIR_NAME = "mgmt"
    private const val FILE_NAME = "upstream_outbox.json"

    private const val MAX_ENTRIES = 500
    private const val MAX_TOTAL_BYTES = 1024 * 1024
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private const val MAX_DELIVERIES = 20

    /** 重放限速：约 20 条/秒, 避免重连瞬间重放风暴挤占心跳与媒体帧。 */
    private const val FLUSH_GAP_MS = 50L

    /**
     * 需要持久化保证的上行消息类型。
     * - SCRIPT_STATUS_UPDATE: 脚本运行终态（success/error）, 净损失后平台缺运行记录;
     * - COMMAND_RESULT: 远程指令终态回执, 净损失后管理端指令永久 pending;
     * - LOG_LINES: 终态关联的脚本日志尾部快照, 净损失后运行详情空白。
     */
    private val DURABLE_TYPES = setOf("SCRIPT_STATUS_UPDATE", "COMMAND_RESULT", "LOG_LINES")

    private const val PRIORITY_LOG_LINES = 0
    private const val PRIORITY_STATUS = 1
    private const val PRIORITY_COMMAND_RESULT = 2

    private data class Entry(
        val msgId: String,
        val type: String,
        /** 最终上线路文（{"type","msgId","payload"}）, 重放时原样发送。 */
        val envelope: String,
        val createdAt: Long,
        var deliveries: Int,
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    @Volatile
    private var loaded = false

    private val flushing = AtomicBoolean(false)

    private val flushExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mgmt-outbox").apply { isDaemon = true }
    }

    fun isDurable(type: String?): Boolean = type != null && DURABLE_TYPES.contains(type)

    /**
     * 入队一条关键消息并持久化, 返回待发送的完整信封文本（调用方直接 ws.send）。
     *
     * 即使当前没有可用连接也必须入队——调用方只需在连接可用时发送返回值;
     * 存储不可用时降级为纯内存外的普通发送（返回信封但不保证重放）。
     */
    fun enqueue(type: String, payload: JSONObject): String {
        val msgId = newMsgId()
        val envelope = JSONObject()
            .put("type", type)
            .put("msgId", msgId)
            .put("payload", payload)
            .toString()
        synchronized(lock) {
            ensureLoadedLocked()
            evictExpiredLocked()
            entries.addLast(Entry(msgId, type, envelope, System.currentTimeMillis(), 0))
            evictOverCapacityLocked()
            persistLocked()
        }
        return envelope
    }

    /**
     * 服务端确认处理完成（或在去重窗口内判定为重复帧）后删除条目。
     */
    fun acknowledge(msgId: String?) {
        if (msgId.isNullOrEmpty()) return
        synchronized(lock) {
            ensureLoadedLocked()
            val removed = entries.removeAll { it.msgId == msgId }
            if (removed) persistLocked()
        }
    }

    /**
     * AUTH_OK 后按 FIFO 重放全部未确认条目（在专用线程异步执行, 不阻塞 WS 回调线程）。
     *
     * @param sender 实际发送函数, 入参为完整信封文本, 返回 WebSocket.send 是否入队成功;
     *               返回 false 表示连接发送缓冲拥塞/已断开, 立即中止本轮, 等下次 AUTH_OK。
     */
    fun flush(sender: (envelope: String) -> Boolean) {
        if (!flushing.compareAndSet(false, true)) return
        flushExecutor.execute {
            try {
                while (true) {
                    val batch: List<Entry>
                    synchronized(lock) {
                        ensureLoadedLocked()
                        evictExpiredLocked()
                        batch = entries.toList()
                    }
                    if (batch.isEmpty()) break
                    var congested = false
                    var changed = false
                    for (entry in batch) {
                        // 重放途中可能已被 ACK 清理
                        val stillQueued = synchronized(lock) { entries.any { it.msgId == entry.msgId } }
                        if (!stillQueued) continue
                        val accepted = runCatching { sender(entry.envelope) }.getOrDefault(false)
                        if (!accepted) {
                            congested = true
                            break
                        }
                        synchronized(lock) {
                            entries.firstOrNull { it.msgId == entry.msgId }?.let {
                                it.deliveries += 1
                                changed = true
                            }
                        }
                        // 专用重放线程内的有意限速（约 20 条/秒）, 不能改成并发发送
                        Thread.sleep(FLUSH_GAP_MS)
                    }
                    if (changed) {
                        synchronized(lock) { persistLocked() }
                    }
                    if (congested) break
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.w(TAG, "outbox flush failed", e)
            } finally {
                flushing.set(false)
            }
        }
    }

    /**
     * 退出登录/切换账号: 未确认条目不得跨账号重放（设备可能被另一租户接入码重新注册,
     * 把上个租户的脚本终态投递给新租户属于越权数据泄漏）。
     */
    fun clear() {
        synchronized(lock) {
            ensureLoadedLocked()
            if (entries.isNotEmpty()) {
                entries.clear()
                persistLocked()
            }
        }
    }

    /** 仅供测试/诊断观察当前积压条数。 */
    fun pendingCount(): Int = synchronized(lock) {
        ensureLoadedLocked()
        entries.size
    }

    private fun newMsgId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun priorityOf(type: String): Int = when (type) {
        "COMMAND_RESULT" -> PRIORITY_COMMAND_RESULT
        "SCRIPT_STATUS_UPDATE" -> PRIORITY_STATUS
        else -> PRIORITY_LOG_LINES
    }

    private fun evictExpiredLocked() {
        val now = System.currentTimeMillis()
        val before = entries.size
        entries.removeAll { now - it.createdAt > MAX_AGE_MS || it.deliveries >= MAX_DELIVERIES }
        if (entries.size < before) {
            Log.w(TAG, "evicted ${before - entries.size} expired/over-delivery outbox entries")
            persistLocked()
        }
    }

    /**
     * 超容量时逐条淘汰: 永远先丢最低优先级（LOG_LINES）, 同级丢最旧;
     * COMMAND_RESULT 只有在全部条目都是同级时才可能被淘汰。
     */
    private fun evictOverCapacityLocked() {
        var bytes = entries.sumOf { it.envelope.toByteArray(Charsets.UTF_8).size }
        var evicted = 0
        while (entries.size > MAX_ENTRIES || bytes > MAX_TOTAL_BYTES) {
            val victim = entries.minWithOrNull(
                compareBy<Entry> { priorityOf(it.type) }.thenBy { it.createdAt },
            ) ?: break
            bytes -= victim.envelope.toByteArray(Charsets.UTF_8).size
            entries.removeAll { it.msgId == victim.msgId }
            evicted += 1
            Log.w(TAG, "outbox over capacity, evicted ${victim.type}(${victim.msgId})")
        }
        if (evicted > 0) persistLocked()
    }

    private fun storeFile(): File? {
        val context = GlobalAppContext.get() ?: return null
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create outbox dir: ${dir.absolutePath}")
            return null
        }
        return File(dir, FILE_NAME)
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val msgId = o.optString("i")
                val type = o.optString("t")
                val envelope = o.optString("e")
                if (msgId.isEmpty() || type.isEmpty() || envelope.isEmpty()) continue
                entries.addLast(
                    Entry(
                        msgId = msgId,
                        type = type,
                        envelope = envelope,
                        createdAt = o.optLong("c", System.currentTimeMillis()),
                        deliveries = o.optInt("d", 0),
                    ),
                )
            }
            evictExpiredLocked()
            evictOverCapacityLocked()
            Log.i(TAG, "loaded ${entries.size} pending outbox entries")
        } catch (e: Exception) {
            Log.w(TAG, "outbox file corrupt, starting empty", e)
            entries.clear()
        }
    }

    /**
     * 原子写：先写临时文件再 rename, 避免进程被杀在半行导致整个发件箱损坏。
     * 调用方必须持有 [lock]。
     */
    private fun persistLocked() {
        val file = storeFile() ?: return
        try {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("i", e.msgId)
                        .put("t", e.type)
                        .put("e", e.envelope)
                        .put("c", e.createdAt)
                        .put("d", e.deliveries),
                )
            }
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // 个别设备/文件系统 rename 失败时退化为直接覆写
                file.writeText(arr.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "persist outbox failed", e)
        }
    }
}
