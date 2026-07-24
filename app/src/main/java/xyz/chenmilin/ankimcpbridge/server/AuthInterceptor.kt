package xyz.chenmilin.ankimcpbridge.server

import xyz.chenmilin.ankimcpbridge.config.TokenManager

/**
 * Bearer Token 鉴权拦截器。
 * /health 不鉴权，/mcp 必须鉴权。
 */
class AuthInterceptor(private val tokenManager: TokenManager) {

    /**
     * 验证 Bearer Token。返回 null 表示通过，否则返回错误消息。
     */
    fun verify(authHeader: String?): String? {
        if (authHeader.isNullOrBlank()) {
            return buildUnauthorizedResponse("Missing Authorization header")
        }

        val bearerPrefix = "Bearer "
        if (!authHeader.startsWith(bearerPrefix, ignoreCase = true)) {
            return buildUnauthorizedResponse("Invalid authorization scheme, expected 'Bearer'")
        }

        val token = authHeader.removePrefix(bearerPrefix).trim()
        if (!tokenManager.verifyToken(token)) {
            return buildUnauthorizedResponse("Invalid token")
        }

        return null // 鉴权通过
    }

    private fun buildUnauthorizedResponse(message: String): String {
        return """{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"Unauthorized","data":{"code":"UNAUTHORIZED","message":"$message"}}}"""
    }
}
