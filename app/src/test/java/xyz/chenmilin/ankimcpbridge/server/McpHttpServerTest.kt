package xyz.chenmilin.ankimcpbridge.server

import io.ktor.client.request.*
import io.ktor.client.request.setBody
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import xyz.chenmilin.ankimcpbridge.anki.FakeAnkiRepository
import xyz.chenmilin.ankimcpbridge.config.InMemoryTokenPersistence
import xyz.chenmilin.ankimcpbridge.config.TokenManager
import xyz.chenmilin.ankimcpbridge.logging.AppLogRepository

class McpHttpServerTest {

    private fun ApplicationTestBuilder.installTestApp(tokenManager: TokenManager = TokenManager(InMemoryTokenPersistence())) {
        application {
            installMcpRouting(tokenManager, FakeAnkiRepository(), AppLogRepository.instance)
        }
    }

    @Test
    fun `health returns HTTP 200`() = testApplication {
        installTestApp()
        val response = client.get("/health")
        assertEquals(200, response.status.value)
    }

    @Test
    fun `health returns valid json with application json content type`() = testApplication {
        installTestApp()
        val response = client.get("/health")
        assertEquals(200, response.status.value)

        val contentType = response.headers[HttpHeaders.ContentType] ?: ""
        assertTrue("Content-Type 应为 application/json，实际: $contentType", contentType.contains("application/json"))

        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("ok", json["status"]?.jsonPrimitive?.content)
        assertEquals("ankidroid-mcp-bridge", json["service"]?.jsonPrimitive?.content)
        assertNotNull("version 不应为空", json["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `health does not require authorization`() = testApplication {
        installTestApp()
        // 不带 Authorization 头访问 /health 应成功（200）
        val response = client.get("/health")
        assertEquals(200, response.status.value)
    }

    @Test
    fun `mcp requires authorization`() = testApplication {
        installTestApp()
        // 不带 Authorization 访问 /mcp 应返回 401
        val response = client.post("/mcp") {
            setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
            contentType(ContentType.Application.Json)
        }
        assertEquals(401, response.status.value)
    }

    @Test
    fun `mcp with valid token returns response`() = testApplication {
        val tokenManager = TokenManager(InMemoryTokenPersistence())
        val token = tokenManager.token
        installTestApp(tokenManager)

        val response = client.post("/mcp") {
            setBody(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""
            )
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
        }

        assertEquals(200, response.status.value)
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        assertNotNull("应返回 result", json["result"])
        assertEquals(
            "ankidroid-mcp-bridge",
            json["result"]?.jsonObject?.get("serverInfo")?.jsonObject?.get("name")?.jsonPrimitive?.content
        )
    }
}
