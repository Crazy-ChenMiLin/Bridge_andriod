package xyz.chenmilin.ankimcpbridge.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 单例，管理 MCP 服务的运行状态。
 * 保证整个 App 内只有一个运行实例视图。
 */
object ServerStateRepository {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }
}
