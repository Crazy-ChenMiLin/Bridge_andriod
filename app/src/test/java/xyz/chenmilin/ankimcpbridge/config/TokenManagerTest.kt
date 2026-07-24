package xyz.chenmilin.ankimcpbridge.config

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * TokenManager 单元测试（无需 Android Context，使用内存持久化）。
 * 重点验证 v0.1.0 的 token 失效 bug 已修复：regenerate 之后旧 token 立即失效。
 */
class TokenManagerTest {

    @Before
    fun setUp() {
        // 清理进程内共享 token，避免用例间串扰
        TokenManager.resetForTest()
    }

    @Test
    fun `verifyToken accepts the generated token`() {
        val tm = TokenManager(InMemoryTokenPersistence())
        assertTrue(tm.verifyToken(tm.token))
    }

    @Test
    fun `verifyToken rejects a wrong token`() {
        val tm = TokenManager(InMemoryTokenPersistence())
        assertFalse(tm.verifyToken("not-the-right-token"))
    }

    @Test
    fun `verifyToken is length sensitive`() {
        val tm = TokenManager(InMemoryTokenPersistence())
        assertFalse(tm.verifyToken(tm.token.dropLast(1)))
    }

    @Test
    fun `regenerate invalidates the previous token immediately`() {
        val tm = TokenManager(InMemoryTokenPersistence())
        val old = tm.token
        assertTrue(tm.verifyToken(old))

        val new = tm.regenerateToken()
        assertNotEquals(old, new)
        // 旧 token 立即失效
        assertFalse(tm.verifyToken(old))
        // 新 token 生效
        assertTrue(tm.verifyToken(new))
        assertEquals(new, tm.token)
    }

    @Test
    fun `regenerated token is reflected across instances`() {
        // 两个实例共享进程内 token 状态
        val a = TokenManager(InMemoryTokenPersistence())
        val b = TokenManager(InMemoryTokenPersistence())
        val old = a.token
        val new = a.regenerateToken()
        // 实例 b 也应识别新 token、拒绝旧 token
        assertTrue(b.verifyToken(new))
        assertFalse(b.verifyToken(old))
    }

    @Test
    fun `generated token has 64 hex chars`() {
        val tm = TokenManager(InMemoryTokenPersistence())
        assertEquals(64, tm.token.length)
    }

    @Test
    fun `constantTimeEquals is order independent but length aware`() {
        assertTrue(TokenManager.constantTimeEquals("abc123", "abc123"))
        assertFalse(TokenManager.constantTimeEquals("abc123", "abc124"))
        assertFalse(TokenManager.constantTimeEquals("abc123", "abc12"))
    }
}
