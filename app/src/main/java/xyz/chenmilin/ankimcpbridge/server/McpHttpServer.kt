package xyz.chenmilin.ankimcpbridge.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.chenmilin.ankimcpbridge.BuildConfig
import xyz.chenmilin.ankimcpbridge.anki.AnkiRepository
import xyz.chenmilin.ankimcpbridge.config.TokenManager
import xyz.chenmilin.ankimcpbridge.logging.AppLogRepository

/**
 * 基于 Ktor Netty 的 MCP HTTP Server。
 * 监听 127.0.0.1 指定端口，提供 /health 和 /mcp 端点。
 */
class McpHttpServer(
    private val port: Int,
    private val tokenManager: TokenManager,
    private val ankiRepository: AnkiRepository,
    private val logRepo: AppLogRepository
) {
    private var server: ApplicationEngine? = null

    suspend fun start() = withContext(Dispatchers.IO) {
        if (server != null) {
            throw IllegalStateException("Server is already running")
        }

        val engine = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            installMcpRouting(tokenManager, ankiRepository, logRepo)
        }

        server = engine
        engine.start(wait = false)
    }

    fun stop() {
        server?.stop(500, 1000)
        server = null
    }
}

/**
 * 安装 MCP 的 HTTP 路由。抽成独立函数便于在单元测试中通过 Ktor 的内存引擎
 * （testApplication）验证 /health 与 /mcp 的行为，无需启动真实 Netty 端口。
 */
fun Application.installMcpRouting(
    tokenManager: TokenManager,
    ankiRepository: AnkiRepository,
    logRepo: AppLogRepository
) {
    val authInterceptor = AuthInterceptor(tokenManager)
    val protocolHandler = McpProtocolHandler(ankiRepository, logRepo)

    routing {
        get("/health") {
            call.respondText(
                contentType = ContentType.Application.Json,
                text = buildHealthResponse()
            )
        }

        post("/mcp") {
            val authHeader = call.request.header("Authorization")
            val authError = authInterceptor.verify(authHeader)
            if (authError != null) {
                call.respondText(
                    status = HttpStatusCode.Unauthorized,
                    contentType = ContentType.Application.Json,
                    text = authError
                )
                return@post
            }

            val body = call.receiveText()
            logRepo.debug("收到 MCP 请求")

            val response = withContext(Dispatchers.Default) {
                protocolHandler.handleRequest(body)
            }

            if (response.isNotEmpty()) {
                call.respondText(
                    contentType = ContentType.Application.Json,
                    text = response
                )
            } else {
                // 通知类请求，返回 202 Accepted
                call.respondText(
                    status = HttpStatusCode.Accepted,
                    text = ""
                )
            }
        }
    }
}

private fun buildHealthResponse(): String {
    return """{"status":"ok","service":"ankidroid-mcp-bridge","version":"${BuildConfig.VERSION_NAME}"}"""
}
