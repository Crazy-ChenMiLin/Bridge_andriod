package xyz.chenmilin.ankimcpbridge.config

import org.junit.Assert.*
import org.junit.Test

/**
 * Token 验证单元测试（固定 Token 1356，无需 Android Context）。
 */
class TokenAuthTest {

    @Test
    fun `correct fixed token passes verification`() {
        assertTrue(TokenManager.constantTimeEquals("1356", "1356"))
    }

    @Test
    fun `wrong token fails verification`() {
        assertFalse(TokenManager.constantTimeEquals("1356", "1234"))
    }

    @Test
    fun `different length token fails`() {
        assertFalse(TokenManager.constantTimeEquals("1356", "135"))
        assertFalse(TokenManager.constantTimeEquals("1356", "13567"))
    }

    @Test
    fun `empty token fails against fixed token`() {
        assertFalse(TokenManager.constantTimeEquals("1356", ""))
    }

    @Test
    fun `fixed token is 1356`() {
        assertEquals("1356", BridgeAuthConfig.FIXED_TOKEN)
    }

    @Test
    fun `authorization value includes Bearer prefix`() {
        assertEquals("Bearer 1356", BridgeAuthConfig.AUTHORIZATION_VALUE)
    }
}
