package xyz.chenmilin.ankimcpbridge.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import xyz.chenmilin.ankimcpbridge.anki.AnkiRepository
import xyz.chenmilin.ankimcpbridge.config.AppConfigRepository
import xyz.chenmilin.ankimcpbridge.config.TokenManager
import xyz.chenmilin.ankimcpbridge.logging.AppLogRepository
import xyz.chenmilin.ankimcpbridge.server.McpHttpServer

class McpForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var httpServer: McpHttpServer? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            NotificationFactory.ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val notificationFactory = NotificationFactory(this)
        notificationFactory.createChannel()

        val configRepo = AppConfigRepository(this)
        val port = configRepo.getPort()

        if (httpServer != null) {
            // Already running
            ServerStateRepository.setRunning(true)
            return START_STICKY
        }

        val notification = notificationFactory.buildServiceNotification(port)
        startForeground(NotificationFactory.NOTIFICATION_ID, notification)

        startServer(port, notificationFactory)
        return START_STICKY
    }

    private fun startServer(port: Int, notificationFactory: NotificationFactory) {
        val app = applicationContext
        val tokenManager = TokenManager(app)
        val configRepo = AppConfigRepository(app)
        val logRepo = AppLogRepository()
        val ankiRepo: AnkiRepository = try {
            createAnkiRepository(app)
        } catch (e: Exception) {
            logRepo.error("创建 AnkiRepository 失败: ${e.message}")
            FakeAnkiRepositoryWrapper()
        }

        httpServer = McpHttpServer(
            port = port,
            tokenManager = tokenManager,
            ankiRepository = ankiRepo,
            logRepo = logRepo
        )

        serviceScope.launch {
            try {
                logRepo.info("正在启动 MCP 服务，端口: $port")
                httpServer?.start()
                ServerStateRepository.setRunning(true)
                logRepo.info("MCP 服务已启动: http://127.0.0.1:$port/mcp")
            } catch (e: Exception) {
                logRepo.error("MCP 服务启动失败: ${e.message}")
                httpServer = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopServer() {
        serviceScope.launch {
            try {
                httpServer?.stop()
                httpServer = null
                ServerStateRepository.setRunning(false)
            } catch (e: Exception) {
                ServerStateRepository.setRunning(false)
            }
        }
    }

    private fun createAnkiRepository(context: android.content.Context): AnkiRepository {
        // 尝试使用真实 AnkiDroid API
        try {
            return xyz.chenmilin.ankimcpbridge.anki.AnkiDroidRepository(context)
        } catch (e: Exception) {
            throw RuntimeException("AnkiDroidRepository initialization failed", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        stopServer()
        ServerStateRepository.setRunning(false)
        super.onDestroy()
    }
}

/** 当 AnkiDroidRepository 不可用时的回退实现 */
private class FakeAnkiRepositoryWrapper : AnkiRepository {
    override fun isAnkiDroidInstalled(): Boolean = false
    override fun hasPermission(): Boolean = false
    override suspend fun listDecks(): List<xyz.chenmilin.ankimcpbridge.anki.AnkiDeck> = emptyList()
    override suspend fun ensureDeck(name: String): xyz.chenmilin.ankimcpbridge.anki.AnkiDeck =
        throw UnsupportedOperationException("AnkiDroid not available")
    override suspend fun addBasicNote(request: xyz.chenmilin.ankimcpbridge.anki.AddBasicNoteRequest): xyz.chenmilin.ankimcpbridge.anki.AddNoteResult =
        throw UnsupportedOperationException("AnkiDroid not available")
    override suspend fun addBasicNotes(request: xyz.chenmilin.ankimcpbridge.anki.AddBasicNotesRequest): xyz.chenmilin.ankimcpbridge.anki.BatchAddResult =
        throw UnsupportedOperationException("AnkiDroid not available")
}
