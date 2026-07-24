package xyz.chenmilin.ankimcpbridge.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 对本地 MCP Server 的 /health 端点做明文 HTTP 探测。
 *
 * 设计要点：
 * - 所有网络 I/O 都在 [Dispatchers.IO] 中执行，不阻塞调用方线程（避免 NetworkOnMainThreadException）。
 * - 区分 2xx 与错误响应；非 2xx 时把状态码与响应体作为异常抛出，便于 UI 展示错误详情。
 * - 通过 [Result] 返回，调用方据此更新 UI 状态；异常不会被吞掉。
 */
object HealthChecker {

    suspend fun check(port: Int): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://127.0.0.1:$port/health")

            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.useCaches = false

            try {
                val statusCode = conn.responseCode
                val stream = if (statusCode in 200..299) {
                    conn.inputStream
                } else {
                    conn.errorStream
                }

                val body = stream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()

                if (statusCode in 200..299) {
                    Result.success(body)
                } else {
                    Result.failure(IllegalStateException("HTTP $statusCode: $body"))
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
