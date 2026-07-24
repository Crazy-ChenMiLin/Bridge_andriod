package xyz.chenmilin.ankimcpbridge.config

import android.content.Context
import java.security.SecureRandom

/**
 * 管理 Bearer Token 的生成、持久化和验证。
 * Token 在首次启动时生成，保存到应用私有 SharedPreferences。
 */
class TokenManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _token: String = loadOrGenerateToken()
    val token: String get() = _token

    private fun loadOrGenerateToken(): String {
        val existing = prefs.getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        return generateToken().also { prefs.edit().putString(KEY_TOKEN, it).apply() }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun regenerateToken(): String {
        val newToken = generateToken()
        prefs.edit().putString(KEY_TOKEN, newToken).apply()
        return newToken
    }

    /**
     * 恒定时间比较，防止时序攻击。
     */
    fun verifyToken(provided: String): Boolean {
        val expected = _token
        if (expected.length != provided.length) return false
        var result = 0
        for (i in expected.indices) {
            result = result or (expected[i].code xor provided[i].code)
        }
        return result == 0
    }

    companion object {
        private const val PREFS_NAME = "ankimcpbridge_token"
        private const val KEY_TOKEN = "bearer_token"
        private const val TOKEN_BYTES = 32
    }
}
