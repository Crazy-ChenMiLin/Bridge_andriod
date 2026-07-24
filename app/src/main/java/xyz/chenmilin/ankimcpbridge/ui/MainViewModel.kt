package xyz.chenmilin.ankimcpbridge.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import xyz.chenmilin.ankimcpbridge.anki.AnkiDroidRepository
import xyz.chenmilin.ankimcpbridge.config.AppConfigRepository
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

    fun toggleTokenVisibility() {
        _uiState.update { it.copy(tokenVisible = !it.tokenVisible) }
    }

    fun regenerateToken() {
        val newToken = tokenManager.regenerateToken()
        _uiState.update { it.copy(token = newToken, tokenVisible = false) }
        logRepo.info("Token 已重新生成")
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
            try {
                val url = java.net.URL("http://127.0.0.1:${_uiState.value.port}/health")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val body = conn.inputStream.bufferedReader().readText()
                _uiState.update { it.copy(testHealthResult = "OK: $body") }
                logRepo.info("健康检查成功")
            } catch (e: Exception) {
                _uiState.update { it.copy(testHealthResult = "失败: ${e.message}") }
                logRepo.error("健康检查失败: ${e.message}")
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

    fun requestAnkiPermission() {
        // 引导用户到 AnkiDroid 授权
        try {
            val intent = app.packageManager.getLaunchIntentForPackage("com.ichi2.anki")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(intent)
                logRepo.info("正在打开 AnkiDroid，请在 AnkiDroid 设置中授权 API 访问")
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

    fun clearLogs() {
        logRepo.clear()
    }
}
