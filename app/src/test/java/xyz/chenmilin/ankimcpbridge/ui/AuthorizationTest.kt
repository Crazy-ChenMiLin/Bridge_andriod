package xyz.chenmilin.ankimcpbridge.ui

import org.junit.Assert.*
import org.junit.Test

class AuthorizationTest {

    @Test
    fun `raw token becomes Bearer token`() {
        assertEquals("Bearer abc123", buildAuthorizationValue("abc123"))
    }

    @Test
    fun `token with surrounding spaces is trimmed`() {
        assertEquals("Bearer abc123", buildAuthorizationValue("  abc123  "))
        assertEquals("Bearer abc123", buildAuthorizationValue("\tabc123\n"))
    }

    @Test
    fun `blank token returns null`() {
        assertNull(buildAuthorizationValue(""))
        assertNull(buildAuthorizationValue("   "))
        assertNull(buildAuthorizationValue("\t\n"))
    }

    @Test
    fun `does not produce hyphenated bearer value`() {
        val value = buildAuthorizationValue("abc123")
        assertNotNull(value)
        assertEquals("Bearer abc123", value)
        assertFalse("不应出现 Bearer- 形式", value!!.contains("Bearer-"))
    }

    @Test
    fun `does not duplicate Bearer prefix`() {
        assertEquals("Bearer abc123", buildAuthorizationValue("Bearer abc123"))
        assertEquals("Bearer abc123", buildAuthorizationValue("bearer abc123"))
        assertEquals("Bearer abc123", buildAuthorizationValue("  Bearer   abc123  "))
    }

    @Test
    fun `only Bearer prefix without token returns null`() {
        assertNull(buildAuthorizationValue("Bearer "))
        assertNull(buildAuthorizationValue("Bearer   "))
        assertNull(buildAuthorizationValue("  bearer   "))
    }
}
