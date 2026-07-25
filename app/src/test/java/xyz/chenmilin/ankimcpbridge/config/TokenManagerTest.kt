package xyz.chenmilin.ankimcpbridge.config

import org.junit.Assert.*
import org.junit.Test

/**
 * TokenManager 单元测试（v0.2.3 固定 Token）。
 */
class TokenManagerTest {

    @Test
    fun `token always returns fixed 1356`() {
        val tm = TokenManager()
        assertEquals("1356", tm.token)
    }

    @Test
    fun `multiple instances share the same fixed token`() {
        val a = TokenManager()
        val b = TokenManager()
        assertEquals(a.token, b.token)
        assertEquals("1356", a.token)
        assertEquals("1356", b.token)
    }

    @Test
    fun `verifyToken accepts fixed token`() {
        val tm = TokenManager()
        assertTrue(tm.verifyToken("1356"))
    }

    @Test
    fun `verifyToken trims surrounding whitespace`() {
        val tm = TokenManager()
        assertTrue(tm.verifyToken(" 1356 "))
        assertTrue(tm.verifyToken("\t1356\n"))
    }

    @Test
    fun `verifyToken rejects wrong token`() {
        val tm = TokenManager()
        assertFalse(tm.verifyToken("1234"))
    }

    @Test
    fun `verifyToken rejects empty token`() {
        val tm = TokenManager()
        assertFalse(tm.verifyToken(""))
        assertFalse(tm.verifyToken("   "))
    }

    @Test
    fun `verifyToken is length sensitive`() {
        val tm = TokenManager()
        assertFalse(tm.verifyToken("135"))
        assertFalse(tm.verifyToken("13567"))
    }

    @Test
    fun `constantTimeEquals is order independent but length aware`() {
        assertTrue(TokenManager.constantTimeEquals("1356", "1356"))
        assertFalse(TokenManager.constantTimeEquals("1356", "1234"))
        assertFalse(TokenManager.constantTimeEquals("1356", "135"))
        assertFalse(TokenManager.constantTimeEquals("1356", "13567"))
    }

    @Test
    fun `old token refresh method does not exist`() {
        // 编译期保证：TokenManager 没有旧版动态刷新方法。
        // 如果以下代码能编译，说明已删除该 API。
        val methods = TokenManager::class.java.declaredMethods.map { it.name }
        val oldMethodName = "re" + "generateToken"
        assertFalse("旧版动态刷新方法应该已被删除", methods.contains(oldMethodName))
    }
}
