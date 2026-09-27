package org.autojs.autojs.mgmt.script

import org.autojs.autojs.execution.ScriptExecution
import org.autojs.autojs.mgmt.client.ManagementPlatformClient
import org.autojs.autojs.execution.ScriptExecutionListener

class ManagementPlatformScriptExecutionListener : ScriptExecutionListener {

    override fun onStart(execution: ScriptExecution) {
        ManagementPlatformClient.onScriptStart(execution)
    }

    override fun onSuccess(execution: ScriptExecution, result: Any?) {
        ManagementPlatformClient.onScriptSuccess(execution)
    }

    override fun onException(execution: ScriptExecution, e: Throwable) {
        ManagementPlatformClient.onScriptException(execution, e)
    }
}
