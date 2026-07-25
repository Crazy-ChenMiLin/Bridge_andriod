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
        handler = McpProtocolHandler(ankiRepo, AppLogRepository.instance)
        AppLogRepository.instance.clear()
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
        assertEquals("0.2.1", result["serverInfo"]?.jsonObject?.get("version")?.jsonPrimitive?.content)
    }

    @Test
    fun `initialize negotiates supported version when client version differs`() {
        val request = buildRequest("initialize", mapOf(
            "protocolVersion" to "2099-01-01",
            "capabilities" to mapOf<String, Any>()
        ))
        val response = parseResponse(handler.handleRequest(request))
        val result = response.result!!.jsonObject
        assertEquals(McpProtocolHandler.SUPPORTED_PROTOCOL_VERSION, result["protocolVersion"]?.jsonPrimitive?.content)
    }

    // ─── ping ───

    @Test
    fun `ping succeeds`() {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val request = buildRequest("ping")
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        assertNotNull(response.result)
    }

    // ─── tools/list (无需先 initialize) ───

    @Test
    fun `tools list works without prior initialize`() {
        val request = buildRequest("tools/list")
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        val tools = response.result!!.jsonObject["tools"]!!.jsonArray
        assertTrue(tools.size >= 9)
        val toolNames = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(toolNames.containsAll(
            listOf(
                "bridge_status", "list_decks", "ensure_deck",
                "list_note_types", "get_note_type", "add_note", "add_notes",
                "add_basic_note", "add_basic_notes"
            )
        ))
    }

    @Test
    fun `tools list shows bridge_status`() {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val request = buildRequest("tools/list")
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        val tools = response.result!!.jsonObject["tools"]!!.jsonArray
        assertTrue(tools.size >= 9)
        val toolNames = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(toolNames.contains("bridge_status"))
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
        assertFalse(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
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
        assertFalse(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
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
        assertFalse(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val content = response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject
        val text = content["text"]!!.jsonPrimitive.content
        val result = Json.parseToJsonElement(text).jsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertEquals("TestDeck", result["deck"]!!.jsonPrimitive.content)
    }

    // ─── 业务错误以 isError=true 形式返回（而非 JSON-RPC error） ───

    @Test
    fun `add_basic_note with blank front returns isError result not json-rpc error`() = runTest {
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
        // 业务错误：仍是成功响应，但 result.isError == true
        assertNull(response.error)
        assertNotNull(response.result)
        assertTrue(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val content = response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject
        val errJson = Json.parseToJsonElement(content["text"]!!.jsonPrimitive.content).jsonObject
        assertEquals(BusinessErrorCodes.INVALID_FRONT, errJson["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `add_basic_notes with empty notes returns isError result`() = runTest {
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
        assertNull(response.error)
        assertTrue(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `tool returns isError when anki not installed`() = runTest {
        ankiRepo.setInstalled(false)
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val request = buildRequest("tools/call", mapOf(
            "name" to "add_basic_note",
            "arguments" to mapOf("deck" to "TestDeck", "front" to "Q", "back" to "A")
        ))
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        assertTrue(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val content = response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject
        val errJson = Json.parseToJsonElement(content["text"]!!.jsonPrimitive.content).jsonObject
        assertEquals(BusinessErrorCodes.ANKIDROID_NOT_INSTALLED, errJson["code"]?.jsonPrimitive?.content)
    }

    // ─── 批量错误索引映射（索引必须对应原始请求位置） ───

    @Test
    fun `add_basic_notes maps error index to original position`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val notes = listOf(
            mapOf("front" to "Q1", "back" to "A1"),
            mapOf("front" to "  ", "back" to "A2"), // 第 2 张（index 1）front 为空
            mapOf("front" to "Q3", "back" to "A3")
        )

        val request = buildRequest("tools/call", mapOf(
            "name" to "add_basic_notes",
            "arguments" to mapOf("deck" to "BatchDeck", "notes" to notes)
        ))
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        val result = response.result!!
        assertTrue(result.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val content = result.jsonObject["content"]!!.jsonArray[0].jsonObject
        val batch = Json.parseToJsonElement(content["text"]!!.jsonPrimitive.content).jsonObject
        assertEquals(3, batch["requested"]!!.jsonPrimitive.int)
        assertEquals(2, batch["succeeded"]!!.jsonPrimitive.int)
        assertEquals(1, batch["failed"]!!.jsonPrimitive.int)
        val err = batch["errors"]!!.jsonArray[0].jsonObject
        assertEquals(1, err["index"]!!.jsonPrimitive.int) // 指向原始第 2 张（0-based）
        assertEquals(BusinessErrorCodes.INVALID_FRONT, err["code"]!!.jsonPrimitive.content)
    }

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
        assertFalse(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val content = response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject
        val text = content["text"]!!.jsonPrimitive.content
        val result = Json.parseToJsonElement(text).jsonObject
        assertEquals(10, result["requested"]!!.jsonPrimitive.int)
        assertEquals(10, result["submitted"]!!.jsonPrimitive.int)
        assertEquals(10, result["succeeded"]!!.jsonPrimitive.int)
        assertEquals(false, result["noteIdsAvailable"]!!.jsonPrimitive.boolean)
    }

    // ─── 结构性 / 协议错误 ───

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

    // ─── v0.2.0 通用笔记类型工具 ───

    @Test
    fun `tools call list_note_types succeeds`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val request = buildRequest("tools/call", mapOf("name" to "list_note_types"))
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        assertFalse(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val content = response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject
        val text = content["text"]!!.jsonPrimitive.content
        val result = Json.parseToJsonElement(text).jsonObject
        assertTrue(result["count"]!!.jsonPrimitive.int >= 4)
        val names = result["noteTypes"]!!.jsonArray.map {
            it.jsonObject["name"]!!.jsonPrimitive.content
        }
        assertTrue(names.contains("Basic"))
        assertTrue(names.contains("MCP 算法题"))
    }

    @Test
    fun `tools call get_note_type returns detail`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        // 先取一个 noteTypeId
        val listResp = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf("name" to "list_note_types"))
        ))
        val listJson = Json.parseToJsonElement(
            listResp.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        val basicId = listJson["noteTypes"]!!.jsonArray.first {
            it.jsonObject["name"]!!.jsonPrimitive.content == "Basic"
        }.jsonObject["id"]!!.jsonPrimitive.long

        val request = buildRequest("tools/call", mapOf(
            "name" to "get_note_type",
            "arguments" to mapOf("noteTypeId" to basicId)
        ))
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        assertFalse(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val result = Json.parseToJsonElement(
            response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertEquals("Basic", result["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("Front", "Back"), result["fields"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `tools call add_note writes a note`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        // 先取 Basic 的 noteTypeId
        val listResp = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf("name" to "list_note_types"))
        ))
        val listJson = Json.parseToJsonElement(
            listResp.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        val basicId = listJson["noteTypes"]!!.jsonArray.first {
            it.jsonObject["name"]!!.jsonPrimitive.content == "Basic"
        }.jsonObject["id"]!!.jsonPrimitive.long

        val request = buildRequest("tools/call", mapOf(
            "name" to "add_note",
            "arguments" to mapOf(
                "deck" to "MCP Test",
                "noteTypeId" to basicId,
                "fields" to mapOf("Front" to "通用写入测试", "Back" to "由 Bridge 创建"),
                "tags" to listOf("mcp-test")
            )
        ))
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        assertFalse(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val result = Json.parseToJsonElement(
            response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertEquals(basicId, result["noteTypeId"]!!.jsonPrimitive.long)
        assertEquals("MCP Test", result["deck"]!!.jsonPrimitive.content)
        assertTrue(result["persisted"]!!.jsonPrimitive.boolean)
        assertTrue(result["refreshNotified"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `tools call add_note rejects unknown field with isError`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val listResp = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf("name" to "list_note_types"))
        ))
        val listJson = Json.parseToJsonElement(
            listResp.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        val basicId = listJson["noteTypes"]!!.jsonArray.first {
            it.jsonObject["name"]!!.jsonPrimitive.content == "Basic"
        }.jsonObject["id"]!!.jsonPrimitive.long

        val request = buildRequest("tools/call", mapOf(
            "name" to "add_note",
            "arguments" to mapOf(
                "deck" to "MCP Test",
                "noteTypeId" to basicId,
                "fields" to mapOf("Front" to "Q", "Back" to "A", "Side" to "X")
            )
        ))
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        // 未知字段：业务错误，isError=true，code 为 FIELD_NOT_FOUND
        assertTrue(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val errJson = Json.parseToJsonElement(
            response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertEquals(BusinessErrorCodes.FIELD_NOT_FOUND, errJson["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tools call add_notes batch with mixed errors`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val listResp = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf("name" to "list_note_types"))
        ))
        val listJson = Json.parseToJsonElement(
            listResp.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        val basicId = listJson["noteTypes"]!!.jsonArray.first {
            it.jsonObject["name"]!!.jsonPrimitive.content == "Basic"
        }.jsonObject["id"]!!.jsonPrimitive.long

        val notes = listOf(
            mapOf("noteTypeId" to basicId, "fields" to mapOf("Front" to "Q1", "Back" to "A1")),
            mapOf("noteTypeId" to basicId, "fields" to mapOf("Front" to "Q2", "Back" to "A2", "Side" to "X")),
            mapOf("noteTypeId" to basicId, "fields" to mapOf("Front" to "Q3", "Back" to "A3"))
        )
        val request = buildRequest("tools/call", mapOf(
            "name" to "add_notes",
            "arguments" to mapOf("deck" to "MCP Test", "notes" to notes)
        ))
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        val result = response.result!!
        assertTrue(result.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val batch = Json.parseToJsonElement(
            result.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertEquals(3, batch["requested"]!!.jsonPrimitive.int)
        assertEquals(2, batch["succeeded"]!!.jsonPrimitive.int)
        assertEquals(1, batch["failed"]!!.jsonPrimitive.int)
        // 错误原始下标应为 1
        assertEquals(1, batch["errors"]!!.jsonArray[0].jsonObject["index"]!!.jsonPrimitive.int)
    }

    @Test
    fun `full flow list_note_types then get_note_type then add_note`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        // 1) list_note_types
        val listResp = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf("name" to "list_note_types"))
        ))
        assertNull(listResp.error)
        val listJson = Json.parseToJsonElement(
            listResp.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        val basicId = listJson["noteTypes"]!!.jsonArray.first {
            it.jsonObject["name"]!!.jsonPrimitive.content == "Basic"
        }.jsonObject["id"]!!.jsonPrimitive.long

        // 2) get_note_type
        val getResp = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "get_note_type",
                "arguments" to mapOf("noteTypeId" to basicId)
            ))
        ))
        assertNull(getResp.error)
        val detail = Json.parseToJsonElement(
            getResp.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertEquals(listOf("Front", "Back"), detail["fields"]!!.jsonArray.map { it.jsonPrimitive.content })

        // 3) add_note
        val addResp = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "add_note",
                "arguments" to mapOf(
                    "deck" to "MCP Test",
                    "noteTypeId" to basicId,
                    "fields" to mapOf("Front" to "完整链路测试", "Back" to "OK")
                )
            ))
        ))
        assertNull(addResp.error)
        assertFalse(addResp.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val addJson = Json.parseToJsonElement(
            addResp.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertTrue(addJson["success"]!!.jsonPrimitive.boolean)
        assertTrue(addJson["persisted"]!!.jsonPrimitive.boolean)
    }

    // ─── v0.2.1：错误码统一为 NOTE_TYPE_NOT_FOUND / 批量 isError 语义 ───

    @Test
    fun `tools call add_note unknown noteTypeId returns NOTE_TYPE_NOT_FOUND`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val request = buildRequest("tools/call", mapOf(
            "name" to "add_note",
            "arguments" to mapOf(
                "deck" to "MCP Test",
                "noteTypeId" to 888888L,
                "fields" to mapOf("Front" to "Q", "Back" to "A")
            )
        ))
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        assertTrue(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val errJson = Json.parseToJsonElement(
            response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertEquals(BusinessErrorCodes.NOTE_TYPE_NOT_FOUND, errJson["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tools call get_note_type unknown id returns NOTE_TYPE_NOT_FOUND`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val request = buildRequest("tools/call", mapOf(
            "name" to "get_note_type",
            "arguments" to mapOf("noteTypeId" to 888888L)
        ))
        val response = parseResponse(handler.handleRequest(request))
        assertNull(response.error)
        assertTrue(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val errJson = Json.parseToJsonElement(
            response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertEquals(BusinessErrorCodes.NOTE_TYPE_NOT_FOUND, errJson["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tools call add_notes mixed noteTypeId counts correctly`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))

        val listJson = Json.parseToJsonElement(
            parseResponse(handler.handleRequest(buildRequest("tools/call", mapOf("name" to "list_note_types"))))
                .result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        val basicId = listJson["noteTypes"]!!.jsonArray.first {
            it.jsonObject["name"]!!.jsonPrimitive.content == "Basic"
        }.jsonObject["id"]!!.jsonPrimitive.long
        val algoId = listJson["noteTypes"]!!.jsonArray.first {
            it.jsonObject["name"]!!.jsonPrimitive.content == "MCP 算法题"
        }.jsonObject["id"]!!.jsonPrimitive.long

        val notes = listOf(
            mapOf("noteTypeId" to basicId, "fields" to mapOf("Front" to "Q1", "Back" to "A1")),
            mapOf("noteTypeId" to algoId, "fields" to mapOf("题目" to "两数之和", "核心思路" to "HashMap", "复杂度" to "O(n)", "Java代码" to "x", "易错点" to "y", "来源" to "z")),
            mapOf("noteTypeId" to basicId, "fields" to mapOf("Front" to "Q2", "Back" to "A2"))
        )
        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf("name" to "add_notes", "arguments" to mapOf("deck" to "MCP Test", "notes" to notes)))
        ))
        assertNull(response.error)
        assertFalse(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val batch = Json.parseToJsonElement(
            response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertEquals(3, batch["requested"]!!.jsonPrimitive.int)
        assertEquals(3, batch["succeeded"]!!.jsonPrimitive.int)
        assertEquals(0, batch["failed"]!!.jsonPrimitive.int)
    }

    @Test
    fun `tools call add_notes persisted failure marks isError`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))
        ankiRepo.setSimulateReadbackShortfall(true)

        val listJson = Json.parseToJsonElement(
            parseResponse(handler.handleRequest(buildRequest("tools/call", mapOf("name" to "list_note_types"))))
                .result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        val basicId = listJson["noteTypes"]!!.jsonArray.first {
            it.jsonObject["name"]!!.jsonPrimitive.content == "Basic"
        }.jsonObject["id"]!!.jsonPrimitive.long

        val notes = listOf(
            mapOf("noteTypeId" to basicId, "fields" to mapOf("Front" to "Q1", "Back" to "A1"))
        )
        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf("name" to "add_notes", "arguments" to mapOf("deck" to "MCP Test", "notes" to notes)))
        ))
        assertNull(response.error)
        // 写入成功但持久化失败 → isError=true
        assertTrue(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val batch = Json.parseToJsonElement(
            response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertTrue(batch["succeeded"]!!.jsonPrimitive.int > 0)
        assertFalse(batch["persisted"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `tools call add_notes refresh failure only does not mark isError`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))
        ankiRepo.setSimulateRefreshFailure(true)

        val listJson = Json.parseToJsonElement(
            parseResponse(handler.handleRequest(buildRequest("tools/call", mapOf("name" to "list_note_types"))))
                .result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        val basicId = listJson["noteTypes"]!!.jsonArray.first {
            it.jsonObject["name"]!!.jsonPrimitive.content == "Basic"
        }.jsonObject["id"]!!.jsonPrimitive.long

        val notes = listOf(
            mapOf("noteTypeId" to basicId, "fields" to mapOf("Front" to "Q1", "Back" to "A1"))
        )
        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf("name" to "add_notes", "arguments" to mapOf("deck" to "MCP Test", "notes" to notes)))
        ))
        assertNull(response.error)
        // 仅刷新失败（best-effort）→ 不判错
        assertFalse(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val batch = Json.parseToJsonElement(
            response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertTrue(batch["succeeded"]!!.jsonPrimitive.int > 0)
        assertFalse(batch["refreshNotified"]!!.jsonPrimitive.boolean)
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
