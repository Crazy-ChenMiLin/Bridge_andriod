package xyz.chenmilin.ankimcpbridge.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import xyz.chenmilin.ankimcpbridge.anki.AnkiDroidRepository
import xyz.chenmilin.ankimcpbridge.anki.AnkiRepository
import xyz.chenmilin.ankimcpbridge.config.AppConfigRepository
import xyz.chenmilin.ankimcpbridge.config.TokenManager
import xyz.chenmilin.ankimcpbridge.logging.AppLogRepository
import xyz.chenmilin.ankimcpbridge.server.McpHttpServer
import java.net.HttpURLConnection
import java.net.URL

class McpForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var httpServer: McpHttpServer? = null
    private val logRepo = AppLogRepository.instance

    companion object {
        private const val TAG = "McpForegroundService"
    }

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
        val ankiRepo: AnkiRepository = try {
            AnkiDroidRepository(app)
        } catch (e: Exception) {
            // AnkiDroidRepository 构造本身不发起 I/O，通常不应失败；
            // 若失败则不启动服务并上报，避免使用假实现掩盖问题。
            val detail = "${e.javaClass.simpleName}: ${e.message}"
            logRepo.error("创建 AnkiRepository 失败: $detail")
            Log.e(TAG, "Create AnkiRepository failed", e)
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

                // 启动后自检：确认端口真正在监听，再对外宣称“运行中”，
                // 避免 UI 显示运行中但端口实际未就绪。
                val portReady = verifyServerRunning(port)
                if (portReady) {
                    ServerStateRepository.setRunning(true)
                    logRepo.info("MCP 服务已启动: http://127.0.0.1:$port/mcp")
                } else {
                    failStart(
                        "MCP 服务启动后端口自检失败（端口可能未真正监听，或已被其他进程占用: $port）",
                        port
                    )
                }
            } catch (e: Exception) {
                val detail = "${e.javaClass.simpleName}: ${e.message}"
                logRepo.error("MCP 服务启动失败: $detail")
                Log.e(TAG, "MCP server start failed", e)
                httpServer?.stop()
                httpServer = null
                ServerStateRepository.setRunning(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * 启动后确认端口确实在监听。Netty 的 [McpHttpServer.start] 是异步绑定，
     * 这里最多重试 3 次、每次间隔 150ms（总耗时不超过 ~500ms），容忍短暂竞态。
     * 仍失败返回 false，由调用方按“启动失败”处理。
     */
    private suspend fun verifyServerRunning(port: Int): Boolean {
        repeat(3) { attempt ->
            try {
                val url = URL("http://127.0.0.1:$port/health")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                conn.useCaches = false
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) return true
            } catch (e: Exception) {
                // 端口尚未就绪，按重试逻辑稍后再次探测
            }
            if (attempt < 2) delay(150)
        }
        return false
    }

    /** 统一处理“启动失败”：清理资源、回退运行状态、停止前台与自身。 */
    private fun failStart(message: String, port: Int) {
        logRepo.error(message)
        Log.e(TAG, "MCP server start failed for port $port")
        try {
            httpServer?.stop()
        } catch (e: Exception) {
            // 忽略关闭异常
        }
        httpServer = null
        ServerStateRepository.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
