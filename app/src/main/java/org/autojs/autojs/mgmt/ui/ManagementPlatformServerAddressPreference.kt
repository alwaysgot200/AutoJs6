package org.autojs.autojs.mgmt.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.AttributeSet
import com.afollestad.materialdialogs.MaterialDialog
import org.autojs.autojs.mgmt.pref.MgmtPref
import org.autojs.autojs.mgmt.service.ManagementPlatformService
import org.autojs.autojs.theme.preference.MaterialPreference
import org.autojs.autojs6.R

class ManagementPlatformServerAddressPreference : MaterialPreference {

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context) : super(context)

    init {
        summaryProvider = SummaryProvider<ManagementPlatformServerAddressPreference> {
            MgmtPref.serverAddress
        }
    }

    override fun onClick() {
        showEditDialog()
        super.onClick()
    }

    private fun showEditDialog() {
        val current = MgmtPref.serverAddress
        MaterialDialog.Builder(prefContext)
            .title(R.string.text_management_platform_server_address)
            .input(
                prefContext.getString(R.string.text_management_platform_server_address),
                current,
            ) { dialog, _ ->
                val input = dialog.inputEditText?.text?.toString()?.trim().orEmpty()
                MgmtPref.serverAddress = input
                startPlatformServiceIfConfigured()
                dialog.dismiss()
                notifyChanged()
            }
            .negativeText(R.string.dialog_button_cancel)
            .negativeColorRes(R.color.dialog_button_default)
            .positiveText(R.string.dialog_button_confirm)
            .positiveColorRes(R.color.dialog_button_attraction)
            .autoDismiss(false)
            .show()
    }

    // 改完配置即以前台服务方式重连 (服务内校验配置完整性, 不完整会自行退出)
    private fun startPlatformServiceIfConfigured() {
        if (MgmtPref.serverAddress.isBlank() ||
            MgmtPref.secret.isBlank()
        ) {
            return
        }
        val intent = Intent(prefContext, ManagementPlatformService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            prefContext.startForegroundService(intent)
        } else {
            prefContext.startService(intent)
        }
    }
}
