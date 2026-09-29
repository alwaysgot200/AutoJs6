package org.autojs.autojs.mgmt.client

/**
 * 平台长连接三态模型 (2026-09-29, 跨境弱网优化 P0):
 *
 * - [CONNECTED]   已通过应用层鉴权 (收到 AUTH_OK), 长连接可用;
 * - [RECOVERING]  连接已断开、退避/即时重连进行中。含本地无网等待网络恢复的场景;
 * - [DISCONNECTED] 持续中断 (超过降级阈值) 且仍在后台重试——属于"提示升级"而非"放弃重连"。
 */
enum class ConnectionState { CONNECTED, RECOVERING, DISCONNECTED }

/**
 * 中断原因, 驱动差异化提示文案。
 *
 * - [NONE] 无中断 (已连接) 或原因尚未确定 (短抖动窗口内);
 * - [LOCAL_NETWORK_LOST] 本机无可用网络 (经 NetworkReachabilityMonitor 判定);
 * - [SERVER_UNREACHABLE] 本机有网, 但服务器不可达 (跨境拥塞/服务器故障);
 * - [AUTH_REJECTED] 服务端明确拒绝 (接入秘钥无效, AUTH_FAILED / 4001)。
 */
enum class ConnectionIssue { NONE, LOCAL_NETWORK_LOST, SERVER_UNREACHABLE, AUTH_REJECTED }

/**
 * 连接状态快照。
 *
 * @param state 三态
 * @param issue 中断原因
 * @param attempts 本轮中断以来的重连次数 (CONNECTED 时归零)
 * @param escalated 是否已过"安静窗口"——true 表示允许向用户展示提示
 */
data class ConnectionStatus(
    val state: ConnectionState,
    val issue: ConnectionIssue,
    val attempts: Int,
    val escalated: Boolean,
) {
    companion object {
        val INITIAL = ConnectionStatus(ConnectionState.DISCONNECTED, ConnectionIssue.NONE, 0, false)
    }
}
