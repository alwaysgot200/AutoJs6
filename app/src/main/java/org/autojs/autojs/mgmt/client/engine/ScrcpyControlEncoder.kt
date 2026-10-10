package org.autojs.autojs.mgmt.client.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * scrcpy v2.4 控制消息编码器（客户端 → server，大端）。
 *
 * @description 仅编码本平台用到的消息类型；字节布局取证自 scrcpy v2.4 源码
 * ControlMessage.java / control_msg.c，升级 scrcpy 时按 MGMT_LAYER.md 易碎点先核对。
 *
 * 触摸消息（TYPE_INJECT_TOUCH_EVENT=2，共 32B）：
 * `[type1][action1][pointerId8][x4][y4][screenWidth2][screenHeight2]`
 * `[pressure2 u16fp][actionButton4][buttons4]`
 *
 * 按键消息（TYPE_INJECT_KEYCODE=0，共 14B）：
 * `[type1][action1][keycode4][repeat4][metastate4]`
 */
object ScrcpyControlEncoder {

    // ===== 消息类型 =====
    const val TYPE_INJECT_KEYCODE: Byte = 0
    const val TYPE_INJECT_TOUCH_EVENT: Byte = 2
    const val TYPE_BACK_OR_SCREEN_ON: Byte = 4
    const val TYPE_EXPAND_NOTIFICATION_PANEL: Byte = 5
    const val TYPE_COLLAPSE_PANEL: Byte = 7

    // ===== MotionEvent action =====
    const val ACTION_DOWN: Byte = 0
    const val ACTION_UP: Byte = 1
    const val ACTION_MOVE: Byte = 2

    // ===== android.view.KeyEvent 键值 =====
    const val KEYCODE_BACK: Int = 4
    const val KEYCODE_HOME: Int = 3
    const val KEYCODE_APP_SWITCH: Int = 187

    /** 触摸压力 u16 固定点满值（server 按 0xFFFF 映射 1.0f） */
    const val PRESSURE_MAX: Short = -1 // 0xFFFF

    /** 触摸消息长度 */
    private const val TOUCH_MSG_SIZE = 32

    /** 按键消息长度 */
    private const val KEYCODE_MSG_SIZE = 14

    /**
     * 构造触摸注入消息（32B）
     *
     * @param pointerId 同一手指 DOWN→UP 必须一致（这里固定使用虚拟手指 0）
     */
    fun touchEvent(
        action: Byte,
        pointerId: Long,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(TOUCH_MSG_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(TYPE_INJECT_TOUCH_EVENT)
        buffer.put(action)
        buffer.putLong(pointerId)
        buffer.putInt(x)
        buffer.putInt(y)
        buffer.putShort(screenWidth.toShort())
        buffer.putShort(screenHeight.toShort())
        // pressure: UP 为 0，其余为满值
        buffer.putShort(if (action == ACTION_UP) 0 else PRESSURE_MAX)
        // actionButton / buttons：手指触摸均为 0
        buffer.putInt(0)
        buffer.putInt(0)
        return buffer.array()
    }

    /**
     * 构造按键注入消息（14B）
     */
    fun keycode(action: Byte, keycode: Int): ByteArray {
        val buffer = ByteBuffer.allocate(KEYCODE_MSG_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(TYPE_INJECT_KEYCODE)
        buffer.put(action)
        buffer.putInt(keycode)
        buffer.putInt(0) // repeat
        buffer.putInt(0) // metastate
        return buffer.array()
    }

    /**
     * 构造「返回/点亮屏幕」消息（2B）：熄屏时点亮，否则注入返回。
     */
    fun backOrScreenOn(action: Byte): ByteArray = byteArrayOf(TYPE_BACK_OR_SCREEN_ON, action)

    /** 展开通知面板（空体，1B） */
    fun expandNotificationPanel(): ByteArray = byteArrayOf(TYPE_EXPAND_NOTIFICATION_PANEL)

    /** 收起通知面板（空体，1B） */
    fun collapsePanel(): ByteArray = byteArrayOf(TYPE_COLLAPSE_PANEL)
}
