package xyz.chenmilin.ankimcpbridge.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import xyz.chenmilin.ankimcpbridge.anki.AnkiDroidRepository
import xyz.chenmilin.ankimcpbridge.config.AppConfigRepository
import xyz.chenmilin.ankimcpbridge.config.BridgeAuthConfig
import xyz.chenmilin.ankimcpbridge.config.TokenManager
import xyz.chenmilin.ankimcpbridge.logging.AppLogRepository
import xyz.chenmilin.ankimcpbridge.service.McpForegroundService
import xyz.chenmilin.ankimcpbridge.service.ServerStateRepository

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()
    private val configRepo = AppConfigRepository(app)
    val tokenManager = TokenManager(app)
    private val logRepo = AppLogRepository.instance
    private val ankiRepo = AnkiDroidRepository(app)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 一次性事件：通知 Activity 发起 Android 权限申请。 */
    private val _permissionRequest = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val permissionRequest: SharedFlow<String> = _permissionRequest.asSharedFlow()

    /** 一次性事件：复制成功后通知 UI 弹出 Snackbar（不携带 Token）。 */
    private val _copyFeedback = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val copyFeedback: SharedFlow<String> = _copyFeedback.asSharedFlow()

    init {
        // 初始加载
        refreshAnkiStatus()
        _uiState.update {
            it.copy(
                token = tokenManager.token,
                port = configRepo.getPort(),
                portInput = configRepo.getPort().toString()
            )
        }

        // 监听服务状态
        viewModelScope.launch {
            ServerStateRepository.isRunning.collect { running ->
                _uiState.update { it.copy(serverRunning = running, portEditable = !running) }
            }
        }

        // 监听日志
        viewModelScope.launch {
            logRepo.logs.collect { entries ->
                val uiEntries = entries.map { e ->
                    LogEntryUi(
                        formattedTime = e.formattedTime,
                        level = e.level.name,
                        message = e.message
                    )
                }
                _uiState.update { it.copy(logEntries = uiEntries) }
            }
        }

        // 监听端口变化
        viewModelScope.launch {
            configRepo.port.collect { port ->
                _uiState.update { it.copy(port = port, portInput = port.toString()) }
            }
        }
    }

    fun refreshAnkiStatus() {
        _uiState.update {
            it.copy(
                ankiInstalled = ankiRepo.isAnkiDroidInstalled(),
                ankiPermissionGranted = ankiRepo.hasPermission()
            )
        }
    }

    fun startService() {
        val intent = Intent(app, McpForegroundService::class.java)
        app.startForegroundService(intent)
        logRepo.info("正在启动 MCP 服务...")
    }

    fun stopService() {
        val intent = Intent(app, McpForegroundService::class.java).apply {
            action = "xyz.chenmilin.ankimcpbridge.STOP_SERVICE"
        }
        app.startService(intent)
        logRepo.info("MCP 服务已停止")
    }

    fun setPortInput(value: String) {
        val filtered = value.filter { it.isDigit() }
        if (filtered.length <= 5) {
            _uiState.update { it.copy(portInput = filtered) }
        }
    }

    fun savePort() {
        val port = _uiState.value.portInput.toIntOrNull()
        if (port != null && port in 1024..65535) {
            configRepo.setPort(port)
            logRepo.info("端口已设置为 $port")
        }
    }

    fun testHealthCheck() {
        viewModelScope.launch {
            val result = HealthChecker.check(_uiState.value.port)
            result.onSuccess { body ->
                _uiState.update { it.copy(testHealthResult = "OK: $body") }
                logRepo.info("健康检查成功")
            }.onFailure { error ->
                val detail = "${error.javaClass.simpleName}: ${error.message}"
                _uiState.update { it.copy(testHealthResult = "失败: $detail") }
                logRepo.error("健康检查失败: $detail")
            }
        }
    }

    fun testListDecks() {
        viewModelScope.launch {
            try {
                val decks = ankiRepo.listDecks()
                val result = decks.joinToString("\n") { "  ${it.id}: ${it.name}" }
                _uiState.update { it.copy(testDecksResult = "找到 ${decks.size} 个牌组:\n$result") }
                logRepo.info("读取牌组成功: ${decks.size} 个")
            } catch (e: Exception) {
                _uiState.update { it.copy(testDecksResult = "失败: ${e.message}") }
                logRepo.error("读取牌组失败: ${e.message}")
            }
        }
    }

    fun testAddNote() {
        viewModelScope.launch {
            try {
                ankiRepo.ensureDeck("MCP Test")
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val request = xyz.chenmilin.ankimcpbridge.anki.AddBasicNoteRequest(
                    deck = "MCP Test",
                    front = "MCP Bridge 测试 ($timestamp)",
                    back = "这是一张由 AnkiDroid MCP Bridge 创建的测试卡片。时间: $timestamp"
                )
                val result = ankiRepo.addBasicNote(request)
                _uiState.update {
                    it.copy(testAddNoteResult = "成功! noteId=${result.noteId}, deck=${result.deck}")
                }
                logRepo.info("测试添加卡片成功, noteId=${result.noteId}")
            } catch (e: Exception) {
                _uiState.update { it.copy(testAddNoteResult = "失败: ${e.message}") }
                logRepo.error("测试添加卡片失败: ${e.message}")
            }
        }
    }

    /**
     * 快速测试：读取本机 AnkiDroid 笔记类型。
     * 展示找到的数量、ID、名称与有序字段列表（不展示完整卡片正文，避免泄露隐私）。
     */
    fun testListNoteTypes() {
        viewModelScope.launch {
            try {
                val types = ankiRepo.listNoteTypes()
                val sb = StringBuilder("找到 ${types.size} 个笔记类型:\n")
                types.take(20).forEach { t ->
                    sb.append("  ${t.id}: ${t.name}\n")
                    sb.append("    字段: ${t.fields.joinToString(", ")}\n")
                }
                if (types.size > 20) sb.append("  ...（仅显示前 20 个）\n")
                _uiState.update { it.copy(testNoteTypesResult = sb.toString().trimEnd()) }
                logRepo.info("读取笔记类型成功: ${types.size} 个")
            } catch (e: Exception) {
                _uiState.update { it.copy(testNoteTypesResult = "失败: ${e.message}") }
                logRepo.error("读取笔记类型失败: ${e.message}")
            }
        }
    }

    /**
     * 快速测试：通用写入一条笔记。
     * 优先寻找 Basic 笔记类型；找不到时不做复杂自动创建，直接提示。
     * 写入后展示 noteId / persisted / refreshNotified / noteTypeId / deck（不展示字段正文）。
     */
    fun testGenericAddNote() {
        viewModelScope.launch {
            try {
                val types = ankiRepo.listNoteTypes()
                val basic = types.firstOrNull { it.name.equals("Basic", ignoreCase = true) }
                    ?: types.firstOrNull { it.fields.size == 2 && it.fields[0].equals("Front", ignoreCase = true) && it.fields[1].equals("Back", ignoreCase = true) }
                if (basic == null) {
                    _uiState.update { it.copy(testGenericAddResult = "未找到 Basic 笔记类型，无法自动写入测试卡片（可在 AnkiDroid 中创建后重试）") }
                    logRepo.warn("通用写入测试：未找到 Basic 笔记类型")
                    return@launch
                }
                ankiRepo.ensureDeck("MCP Test")
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val request = xyz.chenmilin.ankimcpbridge.anki.AddGenericNoteRequest(
                    deck = "MCP Test",
                    noteTypeId = basic.id,
                    fields = mapOf(
                        "Front" to "MCP 通用写入测试 ($timestamp)",
                        "Back" to "由 AnkiDroid MCP Bridge 通用接口创建。"
                    ),
                    tags = listOf("mcp-test")
                )
                val result = ankiRepo.addNote(request)
                _uiState.update {
                    it.copy(
                        testGenericAddResult = buildString {
                            append("写入结果:\n")
                            append("  noteId=${result.noteId}\n")
                            append("  noteTypeId=${result.noteTypeId}\n")
                            append("  deck=${result.deck}\n")
                            append("  persisted=${result.persisted}\n")
                            append("  refreshNotified=${result.refreshNotified}")
                        }
                    )
                }
                logRepo.info("通用写入测试成功, noteId=${result.noteId}, persisted=${result.persisted}")
            } catch (e: Exception) {
                _uiState.update { it.copy(testGenericAddResult = "失败: ${e.message}") }
                logRepo.error("通用写入测试失败: ${e.message}")
            }
        }
    }

    fun requestAnkiPermission() {
        // 由 Activity 发起系统权限申请；若 Activity 未能处理，再引导用户打开 AnkiDroid。
        _permissionRequest.tryEmit(AnkiDroidRepository.READ_WRITE_PERMISSION)
        logRepo.info("等待用户授予 AnkiDroid API 权限...")
    }

    /** Activity 将权限申请结果回传到这里。 */
    fun onPermissionResult(permission: String, granted: Boolean) {
        if (granted) {
            logRepo.info("权限已授权: $permission")
        } else {
            logRepo.warn("权限被拒绝: $permission")
        }
        refreshAnkiStatus()
    }

    /** 备用入口：直接打开 AnkiDroid，让用户在 AnkiDroid 设置里启用 API。 */
    fun openAnkiDroidSettings() {
        try {
            val intent = app.packageManager.getLaunchIntentForPackage("com.ichi2.anki")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(intent)
                logRepo.info("已打开 AnkiDroid，请在其设置中启用 AnkiDroid API")
            } else {
                logRepo.error("无法打开 AnkiDroid")
            }
        } catch (e: Exception) {
            logRepo.error("打开 AnkiDroid 失败: ${e.message}")
        }
    }

    fun copyToClipboard(text: String) {
        val clipboard = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MCP", text))
        logRepo.info("已复制到剪贴板")
    }

    /**
     * 复制“可直接粘贴到 RakaHub 的 Authorization 请求头值”：`Bearer 1356`。
     * 用户无需手动输入 Bearer / 空格 / 横杠，复制出来即完整值。
     * 复制后通过 [_copyFeedback] 通知 UI 给出可见反馈（不含 Token）。
     */
    fun copyRakaHubAuthorization() {
        val clipboard = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("RakaHub Authorization", BridgeAuthConfig.AUTHORIZATION_VALUE)
        )
        logRepo.info("已复制 RakaHub Authorization 请求头值")
        _copyFeedback.tryEmit("已复制，可直接粘贴到 RakaHub")
    }

    fun clearLogs() {
        logRepo.clear()
    }
}
