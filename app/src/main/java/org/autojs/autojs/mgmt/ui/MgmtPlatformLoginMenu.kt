package org.autojs.autojs.mgmt.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.DialogAction
import com.afollestad.materialdialogs.MaterialDialog
import org.autojs.autojs.mgmt.Mgmt
import org.autojs.autojs.mgmt.client.ConnectionState
import org.autojs.autojs.mgmt.client.ConnectionStatus
import org.autojs.autojs.mgmt.client.ManagementPlatformClient
import org.autojs.autojs.mgmt.pref.MgmtPref
import org.autojs.autojs.ui.main.drawer.DrawerMenuGroup
import org.autojs.autojs.ui.main.drawer.DrawerMenuItem
import org.autojs.autojs.ui.main.drawer.DrawerMenuItemViewHolder
import org.autojs.autojs.util.ClipboardUtils
import org.autojs.autojs.util.ViewUtils
import org.autojs.autojs6.R
import org.autojs.autojs6.databinding.MgmtDialogLoginBinding
import org.autojs.autojs6.databinding.MgmtDialogLoggedInBinding
import java.lang.ref.WeakReference

/**
 * 左侧抽屉「登录」分区 (挂钩 H9) 的全部逻辑, 由 [Mgmt.decorateDrawerMenuItems] 一行委托挂入。
 *
 * - 未登录: 单行「登录平台」, 点击弹出商业化表单 (服务器地址 + 平台 API Key, 自动回填上次凭证),
 *   弹窗内以 mode=test 快速鉴权, 成功才置登录态并拉起前台服务建立正式持久连接;
 *   连接进度只在弹窗内展示, 抽屉行不转圈 (避免状态竞态留下永久进度条)。
 * - 已登录: 单行「已登录」, 点击弹信息卡 (租户名/设备 ID/服务器, 设备 ID 一键复制), 可退出登录。
 *   退出只翻登录态开关, 凭证保留供下次回填, 服务与 WS 立即停止且不自动重连。
 *
 * 生命周期: 进程级单例持有一个连接状态监听, 对当前抽屉条目/ViewHolder 仅持弱引用——
 * DrawerFragment 销毁后条目可随 Activity 一并 GC, 不造成泄漏; 状态变化时若条目正在展示,
 * 经 ViewHolder 的 adapterPosition 精准刷新本行; 抽屉关闭期间错过的刷新, 在 View 重新
 * attach (再次打开抽屉) 时经 OnAttachStateChangeListener 自愈补刷。
 */
object MgmtPlatformLoginMenu {

    /**
     * 动态标题条目: 构造时固定 mTitle (equals/hashCode 依据, 列表中仅一个实例),
     * 覆写 getTitle() 在「登录平台 / 已登录」间切换。
     */
    private class LoginDrawerItem :
        DrawerMenuItem(R.drawable.mgmt_ic_login, R.string.mgmt_text_login_platform) {

        @Volatile
        var displayTitleRes: Int = R.string.mgmt_text_login_platform

        override fun getTitle(): Int = displayTitleRes
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var weakItem: WeakReference<LoginDrawerItem?> = WeakReference(null)
    private var weakHolder: WeakReference<DrawerMenuItemViewHolder?> = WeakReference(null)

    init {
        // Client 保证回调 post 到主线程。
        ManagementPlatformClient.addConnectionStateListener { newStatus ->
            render(newStatus)
        }
    }

    /**
     * 在官方抽屉条目列表最前插入「登录」分区 (group + 单条目), 返回新列表, 不修改原列表。
     */
    @JvmStatic
    fun decorate(origin: List<DrawerMenuItem>): List<DrawerMenuItem> {
        val item = LoginDrawerItem()
        item.setAction { holder ->
            weakHolder = WeakReference(holder)
            val ctx = holder.itemView.context
            try {
                installAttachSelfHeal(holder)
                if (ManagementPlatformClient.isConnected) {
                    showLoggedDialog(ctx)
                } else {
                    showLoginDialog(ctx)
                }
            } catch (e: Throwable) {
                // 薄层弹窗异常绝不允许拖垮宿主抽屉。
                android.util.Log.e("MgmtLoginMenu", "login row action failed", e)
                e.message?.let { msg -> ViewUtils.showToast(ctx, msg, true) }
            }
        }
        weakItem = WeakReference(item)
        renderItem(item, ManagementPlatformClient.status)

        return buildList {
            add(DrawerMenuGroup(R.string.mgmt_text_login_group))
            add(item)
            addAll(origin)
        }
    }

    /**
     * 在条目 View 上挂一次性 attach 监听: 抽屉关闭期间错过的状态刷新,
     * 在下次打开抽屉、View 重新挂载时按条目最新状态补刷, 杜绝标题/进度滞留。
     */
    private fun installAttachSelfHeal(holder: DrawerMenuItemViewHolder) {
        val view = holder.itemView
        if (view.getTag(R.id.mgmt_tag_attach_self_heal) == true) {
            return
        }
        view.setTag(R.id.mgmt_tag_attach_self_heal, true)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                mainHandler.post { refreshHolder(holder) }
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    private fun render(newStatus: ConnectionStatus) {
        val item = weakItem.get() ?: return
        renderItem(item, newStatus)
        val holder = weakHolder.get() ?: return
        refreshHolder(holder)
    }

    private fun refreshHolder(holder: DrawerMenuItemViewHolder) {
        val recyclerView = holder.itemView.parent as? RecyclerView ?: return
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            recyclerView.adapter?.notifyItemChanged(position)
        }
    }

    private fun renderItem(item: LoginDrawerItem, newStatus: ConnectionStatus) {
        val connected = newStatus.state == ConnectionState.CONNECTED
        item.displayTitleRes = if (connected) {
            R.string.mgmt_text_logged_in
        } else {
            R.string.mgmt_text_login_platform
        }
        // 已连接: 副标题展示当前服务器; 重连中: 轻提示"正在自动重连"; 未登录不显示。
        item.subtitle = when {
            connected -> MgmtPref.serverAddress.trim()
            newStatus.state == ConnectionState.RECOVERING ->
                org.autojs.autojs.app.GlobalAppContext.get()
                    .getString(R.string.mgmt_text_recovering)
            else -> null
        }
        item.isProgress = false
    }

    private fun showLoginDialog(ctx: Context) {
        // 宿主 AppTheme 为 AppCompat, 弹窗内 OutlinedBox TextInputLayout 需要 MaterialComponents
        // 主题; 用 Bridge 主题仅包裹本视图的 inflate, 不动官方主题。
        val themedCtx = android.view.ContextThemeWrapper(ctx, R.style.MgmtThemeMaterialBridge)
        val binding = MgmtDialogLoginBinding.inflate(LayoutInflater.from(themedCtx))
        // 退出登录不清空凭证, 二次登录自动回填上次地址/API Key。
        binding.mgmtServerAddress.setText(MgmtPref.serverAddress)
        binding.mgmtApiKey.setText(MgmtPref.secret)
        // 从未配置过地址时, 后台线程获取 WiFi 网关作为建议值 (WifiService binder,
        // 严禁主线程调用), 回到主线程时仅在用户尚未输入的情况下预填。
        if (MgmtPref.serverAddress.isEmpty()) {
            Thread({
                val gateway = runCatching {
                    org.autojs.autojs.util.NetworkUtils.getGatewayAddress()
                }.getOrNull()?.takeIf { it.isNotEmpty() && it != "0.0.0.0" }
                if (gateway != null) {
                    mainHandler.post {
                        if (binding.mgmtServerAddress.text?.isNotEmpty() != true) {
                            binding.mgmtServerAddress.setText(gateway)
                        }
                    }
                }
            }, "mgmt-gateway-suggest").apply { isDaemon = true }.start()
        }
        binding.mgmtLoginError.visibility = View.GONE

        lateinit var dialog: MaterialDialog

        fun setBusy(busy: Boolean) {
            binding.mgmtLoginProgress.visibility = if (busy) View.VISIBLE else View.GONE
            binding.mgmtServerAddressLayout.isEnabled = !busy
            binding.mgmtApiKeyLayout.isEnabled = !busy
            binding.mgmtServerAddress.isEnabled = !busy
            binding.mgmtApiKey.isEnabled = !busy
            dialog.getActionButton(DialogAction.POSITIVE)?.isEnabled = !busy
            dialog.getActionButton(DialogAction.NEGATIVE)?.isEnabled = !busy
        }

        fun showError(message: String?) {
            binding.mgmtLoginError.text = message ?: ctx.getString(R.string.mgmt_text_login_failed_generic)
            binding.mgmtLoginError.visibility = View.VISIBLE
        }

        dialog = MaterialDialog.Builder(ctx)
            .customView(binding.root, false)
            .negativeText(R.string.dialog_button_cancel)
            .negativeColorRes(R.color.dialog_button_default)
            .positiveText(R.string.mgmt_text_login_platform)
            .positiveColorRes(R.color.dialog_button_attraction)
            .autoDismiss(false)
            .onPositive { d, _ ->
                val address = binding.mgmtServerAddress.text?.toString()?.trim().orEmpty()
                val apiKey = binding.mgmtApiKey.text?.toString()?.trim().orEmpty()
                if (address.isEmpty() || apiKey.isEmpty()) {
                    showError(ctx.getString(R.string.mgmt_text_login_empty_input))
                    return@onPositive
                }
                // 先存凭证供回填与 testConnection 使用; 登录态开关仅在鉴权成功后置位。
                MgmtPref.serverAddress = address
                MgmtPref.secret = apiKey
                binding.mgmtLoginError.visibility = View.GONE
                setBusy(true)

                // 先以 mode=test 快速鉴权 (5s 兜底超时), 通过再置登录态并拉正式持久连接。
                ManagementPlatformClient.testConnection { success, message ->
                    mainHandler.post {
                        if (success) {
                            MgmtPref.sessionEnabled = true
                            if (d.isShowing) {
                                d.dismiss()
                            }
                            Mgmt.startManagementService(ctx.applicationContext)
                            // 正式连接的 AUTH_OK 由连接状态监听收尾并刷新条目。
                            ViewUtils.showToast(ctx, R.string.text_management_platform_connection_success)
                        } else {
                            if (d.isShowing) {
                                setBusy(false)
                                showError(message)
                            }
                        }
                    }
                }
            }
            .onNegative { d, _ -> d.dismiss() }
            .build()

        dialog.show()
    }

    private fun showLoggedDialog(ctx: Context) {
        val binding = MgmtDialogLoggedInBinding.inflate(LayoutInflater.from(ctx))
        val username = ManagementPlatformClient.tenantName
            ?: ManagementPlatformClient.tenantId
            ?: "-"
        val deviceId = ManagementPlatformClient.deviceId()

        binding.mgmtLoggedUsername.text = username
        binding.mgmtLoggedDeviceId.text = deviceId
        binding.mgmtLoggedServer.text = MgmtPref.serverAddress.trim().ifEmpty { "-" }
        binding.mgmtLoggedCopyId.setOnClickListener {
            ClipboardUtils.setClip(ctx, deviceId)
            ViewUtils.showToast(ctx, R.string.mgmt_text_copied)
        }

        MaterialDialog.Builder(ctx)
            .customView(binding.root, false)
            .negativeText(R.string.mgmt_text_close)
            .negativeColorRes(R.color.dialog_button_default)
            .positiveText(R.string.mgmt_action_logout)
            .positiveColorRes(R.color.mgmt_color_logout)
            .onPositive { dialog, _ ->
                dialog.dismiss()
                Mgmt.logout(ctx.applicationContext)
                ViewUtils.showToast(ctx, R.string.mgmt_text_logged_out)
                // 连接状态监听随即回调并把条目刷新为「登录平台」。
            }
            .show()
    }
}
