package xyz.chenmilin.ankimcpbridge.config

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 管理应用配置：端口、Token 持久化。
 */
class AppConfigRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _port = MutableStateFlow(prefs.getInt(KEY_PORT, DEFAULT_PORT))
    val port: StateFlow<Int> = _port

    fun getPort(): Int = _port.value

    fun setPort(port: Int) {
        _port.value = port
        prefs.edit().putInt(KEY_PORT, port).apply()
    }

    companion object {
        const val PREFS_NAME = "ankimcpbridge_prefs"
        const val KEY_PORT = "mcp_port"
        const val DEFAULT_PORT = 8766
    }
}
