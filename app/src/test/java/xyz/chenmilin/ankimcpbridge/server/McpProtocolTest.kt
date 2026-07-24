package xyz.chenmilin.ankimcpbridge.server

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import xyz.chenmilin.ankimcpbridge.anki.FakeAnkiRepository
import xyz.chenmilin.ankimcpbridge.logging.AppLogRepository

class McpProtocolTest {

    private lateinit var handler: McpProtocolHandler
    private lateinit var ankiRepo: FakeAnkiRepository

    @Before
    fun setup() {
        ankiRepo = FakeAnkiRepository()
        handler = McpProtocolHandler(ankiRepo, AppLogRepository())
    }

    // ─── initialize ───

    @Test
    fun `initialize succeeds`() {
        val request = buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>(),
            "clientInfo" to mapOf("name" to "test-client", "version" to "1.0")
        ))
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        assertNotNull(response.result)
        val result = response.result!!.jsonObject
        assertEquals("2024-11-05", result["protocolVersion"]?.jsonPrimitive?.content)
        assertEquals("ankidroid-mcp-bridge", result["serverInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
    }

    // ─── ping ───

    @Test
    fun `ping succeeds`() {
        // 先初始化
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val request = buildRequest("ping")
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        assertNotNull(response.result)
    }

    // ─── tools/list ───

    @Test
    fun `tools list shows bridge_status`() {
        // 初始化
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val request = buildRequest("tools/list")
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        val tools = response.result!!.jsonObject["tools"]!!.jsonArray
        assertTrue(tools.size >= 5)

        val toolNames = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(toolNames.contains("bridge_status"))
        assertTrue(toolNames.contains("list_decks"))
        assertTrue(toolNames.contains("ensure_deck"))
        assertTrue(toolNames.contains("add_basic_note"))
        assertTrue(toolNames.contains("add_basic_notes"))
    }

    // ─── tools/call bridge_status ───

    @Test
    fun `tools call bridge_status succeeds`() {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val request = buildRequest("tools/call", mapOf(
            "name" to "bridge_status",
            "arguments" to mapOf<String, Any>()
        ))
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        val content = response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject
        val text = content["text"]!!.jsonPrimitive.content
        val statusJson = Json.parseToJsonElement(text).jsonObject
        assertTrue(statusJson["serverRunning"]!!.jsonPrimitive.boolean)
    }

    // ─── tools/call unknown tool ───

    @Test
    fun `tools call unknown tool returns error`() {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val request = buildRequest("tools/call", mapOf(
            "name" to "nonexistent_tool"
        ))
        val response = parseResponse(handler.handleRequest(request))
        assertNotNull(response.error)
        assertEquals(McpErrorCodes.METHOD_NOT_FOUND, response.error!!.code)
    }

    // ─── tools/call list_decks ───

    @Test
    fun `tools call list_decks succeeds`() = runTest {
        ankiRepo.ensureDeck("TestDeck")

        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val request = buildRequest("tools/call", mapOf(
            "name" to "list_decks"
        ))
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        val content = response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject
        val text = content["text"]!!.jsonPrimitive.content
        val decksJson = Json.parseToJsonElement(text).jsonObject
        assertEquals(1, decksJson["count"]!!.jsonPrimitive.int)
    }

    // ─── tools/call ensure_deck ───

    @Test
    fun `tools call ensure_deck creates deck`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val request = buildRequest("tools/call", mapOf(
            "name" to "ensure_deck",
            "arguments" to mapOf("name" to "NewDeck")
        ))
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        val content = response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject
        val text = content["text"]!!.jsonPrimitive.content
        val result = Json.parseToJsonElement(text).jsonObject
        assertEquals("NewDeck", result["name"]!!.jsonPrimitive.content)
        assertTrue(result["created"]!!.jsonPrimitive.boolean)
    }

    // ─── tools/call add_basic_note ───

    @Test
    fun `tools call add_basic_note succeeds`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val request = buildRequest("tools/call", mapOf(
            "name" to "add_basic_note",
            "arguments" to mapOf(
                "deck" to "TestDeck",
                "front" to "What is Kotlin?",
                "back" to "A programming language",
                "tags" to listOf("kotlin", "programming")
            )
        ))
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        val content = response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject
        val text = content["text"]!!.jsonPrimitive.content
        val result = Json.parseToJsonElement(text).jsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertEquals("TestDeck", result["deck"]!!.jsonPrimitive.content)
    }

    // ─── tools/call add_basic_notes ───

    @Test
    fun `tools call add_basic_notes succeeds`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val notes = (1..10).map { i ->
            mapOf(
                "front" to "Question $i",
                "back" to "Answer $i",
                "tags" to listOf("batch-test")
            )
        }

        val request = buildRequest("tools/call", mapOf(
            "name" to "add_basic_notes",
            "arguments" to mapOf(
                "deck" to "BatchDeck",
                "notes" to notes
            )
        ))
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        val content = response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject
        val text = content["text"]!!.jsonPrimitive.content
        val result = Json.parseToJsonElement(text).jsonObject
        assertEquals(10, result["requested"]!!.jsonPrimitive.int)
        assertEquals(10, result["succeeded"]!!.jsonPrimitive.int)
    }

    // ─── 错误处理 ───

    @Test
    fun `missing method returns error`() {
        val body = """{"jsonrpc":"2.0","id":1}"""
        val response = parseResponse(handler.handleRequest(body))
        assertNotNull(response.error)
    }

    @Test
    fun `invalid JSON returns parse error`() {
        val body = "not json"
        val response = parseResponse(handler.handleRequest(body))
        assertNotNull(response.error)
        assertEquals(McpErrorCodes.PARSE_ERROR, response.error!!.code)
    }

    @Test
    fun `unknown method returns error`() {
        val request = buildRequest("unknown.method")
        val response = parseResponse(handler.handleRequest(request))
        assertNotNull(response.error)
        assertEquals(McpErrorCodes.METHOD_NOT_FOUND, response.error!!.code)
    }

    @Test
    fun `tools list before initialize returns error`() {
        val request = buildRequest("tools/list")
        val response = parseResponse(handler.handleRequest(request))
        assertNotNull(response.error)
        assertEquals(McpErrorCodes.SERVER_NOT_INITIALIZED, response.error!!.code)
    }

    // ─── 参数校验 ───

    @Test
    fun `add_basic_note with blank front returns error`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val request = buildRequest("tools/call", mapOf(
            "name" to "add_basic_note",
            "arguments" to mapOf(
                "deck" to "TestDeck",
                "front" to "   ",
                "back" to "Answer"
            )
        ))
        val response = parseResponse(handler.handleRequest(request))
        assertNotNull(response.error)
    }

    @Test
    fun `add_basic_notes with empty notes returns error`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val request = buildRequest("tools/call", mapOf(
            "name" to "add_basic_notes",
            "arguments" to mapOf(
                "deck" to "TestDeck",
                "notes" to emptyList<Any>()
            )
        ))
        val response = parseResponse(handler.handleRequest(request))
        assertNotNull(response.error)
    }

    // ─── 辅助方法 ───

    private fun buildRequest(method: String, params: Map<String, Any>? = null): String {
        val obj = mutableMapOf<String, JsonElement>(
            "jsonrpc" to JsonPrimitive("2.0"),
            "id" to JsonPrimitive(1),
            "method" to JsonPrimitive(method)
        )
        if (params != null) {
            obj["params"] = mapToJsonElement(params)
        }
        return JsonObject(obj).toString()
    }

    private fun mapToJsonElement(map: Map<String, Any>): JsonElement {
        val entries = map.map { (key, value) ->
            key to when (value) {
                is String -> JsonPrimitive(value)
                is Int -> JsonPrimitive(value)
                is Long -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is List<*> -> JsonArray(value.map { v ->
                    when (v) {
                        is Map<*, *> -> mapToJsonElement(v as Map<String, Any>)
                        is String -> JsonPrimitive(v)
                        else -> JsonPrimitive(v.toString())
                    }
                })
                is Map<*, *> -> mapToJsonElement(value as Map<String, Any>)
                else -> JsonPrimitive(value.toString())
            }
        }
        return JsonObject(entries.toMap())
    }

    private fun parseResponse(text: String): JsonRpcResponse {
        val json = Json.parseToJsonElement(text).jsonObject
        return JsonRpcResponse(
            id = json["id"],
            result = json["result"],
            error = json["error"]?.let {
                JsonRpcError(
                    code = it.jsonObject["code"]!!.jsonPrimitive.int,
                    message = it.jsonObject["message"]!!.jsonPrimitive.content,
                    data = it.jsonObject["data"]
                )
            }
        )
    }
}
