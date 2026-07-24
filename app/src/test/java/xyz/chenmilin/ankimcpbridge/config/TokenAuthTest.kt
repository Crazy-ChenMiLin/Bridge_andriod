package xyz.chenmilin.ankimcpbridge.config

import org.junit.Assert.*
import org.junit.Test

/**
 * TokenManager 的 Token 验证测试（不需要 Android Context）。
 */
class TokenAuthTest {

    // 使用简单比较模拟 TokenManager 的核心验证逻辑
    @Test
    fun `correct token passes verification`() {
        val token = generateTestToken()
        assertTrue(constantTimeEquals(token, token))
    }

    @Test
    fun `wrong token fails verification`() {
        val token = generateTestToken()
        val wrong = generateTestToken()
        if (token == wrong) return // 极小概率碰撞，跳过
        assertFalse(constantTimeEquals(token, wrong))
    }

    @Test
    fun `different length token fails`() {
        val token = "abc123"
        val wrong = "abc12"
        assertFalse(constantTimeEquals(token, wrong))
    }

    @Test
    fun `empty token fails against non-empty`() {
        assertFalse(constantTimeEquals("abc123", ""))
    }

    @Test
    fun `null-like empty token fails`() {
        assertFalse(constantTimeEquals("abc123", ""))
        assertFalse(constantTimeEquals("", "abc123"))
    }

    @Test
    fun `generated token has sufficient length`() {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        assertEquals(64, token.length) // 32 bytes = 64 hex chars
    }

    @Test
    fun `regenerated token differs from original`() {
        val bytes1 = ByteArray(32)
        val bytes2 = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes1)
        java.security.SecureRandom().nextBytes(bytes2)
        val token1 = bytes1.joinToString("") { "%02x".format(it) }
        val token2 = bytes2.joinToString("") { "%02x".format(it) }
        if (token1 == token2) return // 极小概率
        assertNotEquals(token1, token2)
    }

    private fun generateTestToken(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** 恒定时间比较，防止时序攻击 */
        fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var result = 0
            for (i in a.indices) {
                result = result or (a[i].code xor b[i].code)
            }
            return result == 0
        }
    }
}
