package xyz.chenmilin.ankimcpbridge.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import xyz.chenmilin.ankimcpbridge.anki.AnkiDroidRepository
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
            // 已在运行
            ServerStateRepository.setRunning(true)
            return START_STICKY
        }

        val notification = notificationFactory.buildServiceNotification(port)
        startForeground(NotificationFactory.NOTIFICATION_ID, notification)

        startServer(port)
        return START_STICKY
    }

    private fun startServer(port: Int) {
        val app = applicationContext
        val tokenManager = TokenManager(app)
        val logRepo = AppLogRepository.instance
        val ankiRepo: AnkiRepository = try {
            AnkiDroidRepository(app)
        } catch (e: Exception) {
            // AnkiDroidRepository 构造本身不发起 I/O，通常不应失败；
            // 若失败则不启动服务并上报，避免使用假实现掩盖问题。
            logRepo.error("创建 AnkiRepository 失败: ${e.message}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
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
                ServerStateRepository.setRunning(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopServer() {
        // 先停止 HTTP Server（同步、阻塞式关闭 Netty），再取消协程作用域，
        // 避免作用域被取消后关闭协程无法执行导致 server 泄漏。
        try {
            httpServer?.stop()
        } catch (e: Exception) {
            // 忽略关闭异常
        }
        httpServer = null
        ServerStateRepository.setRunning(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServer()
        serviceScope.cancel()
        ServerStateRepository.setRunning(false)
        super.onDestroy()
    }
}
