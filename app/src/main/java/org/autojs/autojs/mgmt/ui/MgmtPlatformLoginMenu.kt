package org.autojs.autojs.mgmt.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebView
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.DialogAction
import com.afollestad.materialdialogs.MaterialDialog
import org.autojs.autojs.app.GlobalAppContext
import org.autojs.autojs.mgmt.Mgmt
import org.autojs.autojs.mgmt.auth.AccountApiClient
import org.autojs.autojs.mgmt.auth.AccountSession
import org.autojs.autojs.mgmt.client.ConnectionState
import org.autojs.autojs.mgmt.client.ManagementPlatformClient
import org.autojs.autojs.ui.main.drawer.DrawerMenuGroup
import org.autojs.autojs.ui.main.drawer.DrawerMenuItem
import org.autojs.autojs.ui.main.drawer.DrawerMenuItemViewHolder
import org.autojs.autojs.ui.main.drawer.DrawerMenuAdapter
import org.autojs.autojs.util.ViewUtils
import org.autojs.autojs6.R
import org.autojs.autojs6.databinding.MgmtDialogLoginBinding
import java.lang.ref.WeakReference

/**
 * 左侧抽屉「账号」分区 (挂钩 H9) 的全部逻辑, 由 [Mgmt.decorateDrawerMenuItems] 一行委托挂入。
 *
 * P1 集成市场地基 (方案 §7/§10.1, A13 验收) 的三态状态机:
 * - 未登录: 「登录」(账号+密码+图形验证码, 调基座 /auth/login) + 「免费注册」
 *   (唯一注册通道: 系统浏览器打开 /admin/register, APP 内不加载注册页);
 * - 已登录未连接: 主行「连接设备」(副标题=账号名·未连接; 接入码缺失时现场拉取重试) + 「退出登录」;
 * - 已连接: 主行「断开连接」(副标题=账号名·在线/连接中, 只断 WS 不掉登录) + 「退出登录」。
 *
 * 账号会话 (JWT) 加密持久化, 重启自动恢复; 连接与会话独立 —— 逛市场/购买无需连接设备。
 *
 * 列表刷新: decorate 仅在抽屉创建时发生一次, 状态变化 (登录/退出/连接状态) 时直接在
 * adapter 的活列表上做 mgmt 段 (group 之后连续的 [MgmtMarkerItem]) 的原地增删并精准
 * notify; 对当前抽屉 View 仅持弱引用, 抽屉关闭期间的变化在下次 decorate 时自然重建。
 */
object MgmtPlatformLoginMenu {

    /** mgmt 自有条目标记, 用于在活列表中定位 mgmt 段边界。 */
    private class MgmtMarkerItem(icon: Int, titleRes: Int) : DrawerMenuItem(icon, titleRes)

    private val mainHandler = Handler(Looper.getMainLooper())

    private var weakRecyclerView: WeakReference<RecyclerView?> = WeakReference(null)

    init {
        // Client/Account 均保证回调 post 到主线程; 再次 post 统一时序。
        ManagementPlatformClient.addConnectionStateListener { _ -> mainHandler.post { rebuild() } }
        AccountSession.addListener { _ -> mainHandler.post { rebuild() } }
    }

    /**
     * 在官方抽屉条目列表最前插入「账号」分区 (group + 当前状态条目), 返回新列表, 不改原列表。
     */
    @JvmStatic
    fun decorate(origin: List<DrawerMenuItem>): List<DrawerMenuItem> = buildList {
        add(DrawerMenuGroup(R.string.mgmt_text_account_group))
        addAll(buildItems())
        addAll(origin)
    }

    // region 三态条目构建

    private fun buildItems(): List<DrawerMenuItem> {
        if (!AccountSession.isLoggedIn) {
            return listOf(
                MgmtMarkerItem(R.drawable.mgmt_ic_login, R.string.mgmt_text_account_login).apply {
                    setAction { holder ->
                        rememberRecyclerView(holder)
                        runCatching { showLoginDialog(holder.itemView.context) }
                            .onFailure { android.util.Log.e("MgmtLoginMenu", "login dialog failed", it) }
                    }
                },
                MgmtMarkerItem(R.drawable.mgmt_ic_person_add, R.string.mgmt_text_register_free).apply {
                    setAction { holder ->
                        rememberRecyclerView(holder)
                        Mgmt.openRegister(holder.itemView.context)
                    }
                },
            )
        }
        val status = ManagementPlatformClient.status
        // RECOVERING 也算"连接中": 自动重连已武装, 主行提供「断开连接」以取消本轮连接。
        val connectionActive = status.state == ConnectionState.CONNECTED ||
            status.state == ConnectionState.RECOVERING
        val accountName = AccountSession.username()?.takeIf { it.isNotEmpty() } ?: "-"
        val statusText = GlobalAppContext.get().getString(
            when (status.state) {
                ConnectionState.CONNECTED -> R.string.mgmt_text_status_online
                ConnectionState.RECOVERING -> R.string.mgmt_text_status_connecting
                else -> R.string.mgmt_text_status_disconnected
            },
        )
        val primary = if (connectionActive) {
            MgmtMarkerItem(R.drawable.mgmt_ic_link_off, R.string.mgmt_text_disconnect_device).apply {
                setAction { holder ->
                    rememberRecyclerView(holder)
                    Mgmt.disconnectDevice(holder.itemView.context.applicationContext)
                }
            }
        } else {
            MgmtMarkerItem(R.drawable.mgmt_ic_link, R.string.mgmt_text_connect_device).apply {
                setAction { holder ->
                    rememberRecyclerView(holder)
                    Mgmt.connectDevice(holder.itemView.context.applicationContext)
                }
            }
        }
        primary.subtitle = GlobalAppContext.get()
            .getString(R.string.mgmt_text_account_subtitle, accountName, statusText)

        val logout = MgmtMarkerItem(R.drawable.mgmt_ic_login, R.string.mgmt_action_logout).apply {
            setAction { holder ->
                rememberRecyclerView(holder)
                confirmLogout(holder.itemView.context)
            }
        }
        return listOf(primary, logout)
    }

    // endregion

    // region 活列表原地刷新

    private fun rememberRecyclerView(holder: DrawerMenuItemViewHolder) {
        installAttachSelfHeal(holder)
        (holder.itemView.parent as? RecyclerView)?.let { weakRecyclerView = WeakReference(it) }
    }

    /**
     * View attach 时自愈补刷, 并登记 RecyclerView 供后续状态变化做原地增删。
     */
    private fun installAttachSelfHeal(holder: DrawerMenuItemViewHolder) {
        val view = holder.itemView
        if (view.getTag(R.id.mgmt_tag_attach_self_heal) == true) return
        view.setTag(R.id.mgmt_tag_attach_self_heal, true)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                mainHandler.post {
                    (v.parent as? RecyclerView)?.let { weakRecyclerView = WeakReference(it) }
                    rebuild()
                }
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    /**
     * 按最新账号/连接状态重建 adapter 活列表中的 mgmt 段 (group 后连续 MgmtMarkerItem)。
     * 抽屉未展示时静默跳过 —— 下次 decorate 会按最新状态构建。
     */
    private fun rebuild() {
        val recyclerView = weakRecyclerView.get() ?: return
        val adapter = recyclerView.adapter as? DrawerMenuAdapter ?: return
        val items = adapter.drawerMenuItems ?: return
        if (items.isEmpty() || items.first() !is DrawerMenuGroup) return

        var endExclusive = 1
        while (endExclusive < items.size && items[endExclusive] is MgmtMarkerItem) {
            endExclusive++
        }
        val fresh = buildItems()
        val oldCount = endExclusive - 1
        if (oldCount == fresh.size && fresh.indices.all { items[it + 1].title == fresh[it].title }) {
            // 条目种类未变 (典型: 在线↔连接中), 仅刷新副标题即可。
            for (i in fresh.indices) {
                val newItem = fresh[i]
                items[i + 1].setSubtitle(newItem.subtitle)
            }
            adapter.notifyItemRangeChanged(1, fresh.size)
            return
        }
        // ArrayList.subList 结构修改会反映到底层列表 (buildList 产物为 ArrayList)。
        items.subList(1, endExclusive).clear()
        items.addAll(1, fresh)
        if (oldCount > 0) adapter.notifyItemRangeRemoved(1, oldCount)
        if (fresh.isNotEmpty()) adapter.notifyItemRangeInserted(1, fresh.size)
        // 组标题 (登录→账号) 如遇旧实例一并刷新。
        adapter.notifyItemChanged(0)
    }

    // endregion

    // region 退出登录确认

    private fun confirmLogout(ctx: Context) {
        val accountName = AccountSession.username() ?: "-"
        MaterialDialog.Builder(ctx)
            .title(R.string.mgmt_action_logout)
            .content(ctx.getString(R.string.mgmt_text_logout_confirm, accountName))
            .negativeText(R.string.dialog_button_cancel)
            .negativeColorRes(R.color.dialog_button_default)
            .positiveText(R.string.mgmt_action_logout)
            .positiveColorRes(R.color.mgmt_color_logout)
            .onPositive { dialog, _ ->
                dialog.dismiss()
                Mgmt.logout(ctx.applicationContext)
            }
            .show()
    }

    // endregion

    // region 账号登录弹窗 (账号 + 密码 + 图形验证码)

    private fun showLoginDialog(ctx: Context) {
        // 宿主 AppTheme 为 AppCompat, 弹窗内 OutlinedBox TextInputLayout 需要 MaterialComponents
        // 主题; 用 Bridge 主题仅包裹本视图的 inflate, 不动官方主题。
        val themedCtx = android.view.ContextThemeWrapper(ctx, R.style.MgmtThemeMaterialBridge)
        val binding = MgmtDialogLoginBinding.inflate(LayoutInflater.from(themedCtx))

        // 验证码会话: 服务端开关关闭时不展示输入行, 登录提交空 code/uuid。
        var captchaEnabled = false
        var captchaUuid: String = ""

        lateinit var dialog: MaterialDialog

        fun setBusy(busy: Boolean) {
            binding.mgmtLoginProgress.visibility = if (busy) View.VISIBLE else View.GONE
            binding.mgmtUsernameLayout.isEnabled = !busy
            binding.mgmtPasswordLayout.isEnabled = !busy
            binding.mgmtCaptchaLayout.isEnabled = !busy
            binding.mgmtUsername.isEnabled = !busy
            binding.mgmtPassword.isEnabled = !busy
            binding.mgmtCaptcha.isEnabled = !busy
            binding.mgmtCaptchaImage.isEnabled = !busy
            dialog.getActionButton(DialogAction.POSITIVE)?.isEnabled = !busy
            dialog.getActionButton(DialogAction.NEGATIVE)?.isEnabled = !busy
        }

        fun showError(message: String?) {
            binding.mgmtLoginError.text =
                message ?: ctx.getString(R.string.mgmt_text_login_failed_generic)
            binding.mgmtLoginError.visibility = View.VISIBLE
        }

        fun loadCaptcha() {
            binding.mgmtCaptchaImage.visibility = View.INVISIBLE
            AccountApiClient.fetchCaptcha { result ->
                if (!dialog.isShowing) return@fetchCaptcha
                result
                    .onSuccess { captcha ->
                        captchaEnabled = captcha.enabled
                        captchaUuid = captcha.uuid
                        if (captcha.enabled && captcha.img.isNotEmpty()) {
                            binding.mgmtCaptchaRow.visibility = View.VISIBLE
                            binding.mgmtCaptchaImage.visibility = View.VISIBLE
                            // 服务端 img 为 svg-captcha 的原始 SVG 标记 (前端走 v-html), 不是 data URI;
                            // WebView 以 image/svg+xml 直接加载, baseUrl=null 避免相对资源解析。
                            runCatching {
                                binding.mgmtCaptchaImage.loadDataWithBaseURL(
                                    null,
                                    captcha.img,
                                    "image/svg+xml",
                                    "UTF-8",
                                    null,
                                )
                            }
                        } else {
                            binding.mgmtCaptchaRow.visibility = View.GONE
                        }
                    }
                    .onFailure {
                        captchaEnabled = true
                        captchaUuid = ""
                        binding.mgmtCaptchaRow.visibility = View.VISIBLE
                        binding.mgmtCaptchaImage.visibility = View.VISIBLE
                        showError(ctx.getString(R.string.mgmt_text_captcha_load_failed))
                    }
            }
        }

        binding.mgmtCaptchaImage.settings.apply {
            // SVG 静态图无需 JS/存储, 最小能力面。
            javaScriptEnabled = false
            setSupportZoom(false)
            builtInZoomControls = false
            loadWithOverviewMode = true
        }
        binding.mgmtCaptchaImage.isClickable = true
        binding.mgmtCaptchaImage.setOnClickListener { loadCaptcha() }

        dialog = MaterialDialog.Builder(ctx)
            .customView(binding.root, false)
            .negativeText(R.string.dialog_button_cancel)
            .negativeColorRes(R.color.dialog_button_default)
            .positiveText(R.string.mgmt_text_account_login)
            .positiveColorRes(R.color.dialog_button_attraction)
            .autoDismiss(false)
            .onPositive { d, _ ->
                val username = binding.mgmtUsername.text?.toString()?.trim().orEmpty()
                val password = binding.mgmtPassword.text?.toString().orEmpty()
                val code = binding.mgmtCaptcha.text?.toString()?.trim().orEmpty()
                if (username.isEmpty() || password.isEmpty() || (captchaEnabled && code.isEmpty())) {
                    showError(ctx.getString(R.string.mgmt_text_login_missing_fields))
                    return@onPositive
                }
                binding.mgmtLoginError.visibility = View.GONE
                setBusy(true)
                AccountSession.login(username, password, code, captchaUuid) { loginResult ->
                    if (!d.isShowing) return@login
                    loginResult
                        .onSuccess { outcome ->
                            d.dismiss()
                            if (outcome.accessCodeReady) {
                                // 接入码已自动写入, 直接拉起 FGS 建正式持久连接。
                                Mgmt.connectDevice(ctx.applicationContext)
                                ViewUtils.showToast(ctx, R.string.mgmt_text_login_success_connected)
                            } else {
                                // 会话已建立, 但接入码拉取失败 (网络/超管): 明确原因, 可点「连接设备」重试。
                                val msg = outcome.notice ?: ctx.getString(R.string.mgmt_text_login_success_pending)
                                ViewUtils.showToast(ctx, msg, true)
                            }
                        }
                        .onFailure { err ->
                            setBusy(false)
                            showError(err.message)
                            // 验证码一次性消费, 失败后必须刷新。
                            binding.mgmtCaptcha.text?.clear()
                            loadCaptcha()
                        }
                }
            }
            .onNegative { d, _ -> d.dismiss() }
            .dismissListener {
                // WebView 随 Dialog 销毁, 防止其持有 Activity Context 泄漏。
                runCatching {
                    binding.mgmtCaptchaImage.apply {
                        stopLoading()
                        loadUrl("about:blank")
                        (parent as? android.view.ViewGroup)?.removeView(this)
                        destroy()
                    }
                }
            }
            .build()

        dialog.show()
        loadCaptcha()
    }

    // endregion
}
