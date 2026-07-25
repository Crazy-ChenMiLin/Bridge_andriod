package xyz.chenmilin.ankimcpbridge.ui

data class UiState(
    // AnkiDroid 状态
    val ankiInstalled: Boolean = false,
    val ankiPermissionGranted: Boolean = false,

    // MCP 服务状态
    val serverRunning: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 8766,

    // Token
    val token: String = "",
    val tokenVisible: Boolean = false,

    // 测试结果
    val testHealthResult: String? = null,
    val testDecksResult: String? = null,
    val testAddNoteResult: String? = null,
    val testNoteTypesResult: String? = null,
    val testGenericAddResult: String? = null,

    // 日志
    val logEntries: List<LogEntryUi> = emptyList(),

    // 端口编辑
    val portInput: String = "8766",
    val portEditable: Boolean = true
)

data class LogEntryUi(
    val formattedTime: String,
    val level: String,
    val message: String
)
