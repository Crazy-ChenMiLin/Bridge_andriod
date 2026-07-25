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
        assertEquals(McpProtocolHandler.SERVER_VERSION, result["serverInfo"]?.jsonObject?.get("version")?.jsonPrimitive?.content)
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

    @Test
    fun `tools list exposes only supported pc compatible aliases`() {
        val response = parseResponse(handler.handleRequest(buildRequest("tools/list")))
        assertNull(response.error)
        val toolNames = response.result!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }

        assertEquals("tools/list 应只暴露当前文档声明的 44 个真实支持工具", 44, toolNames.size)
        assertTrue(toolNames.containsAll(
            listOf(
                "listDecks", "deckNames", "deckNamesAndIds", "createDeck",
                "modelNames", "modelNamesAndIds", "modelFieldNames", "addNote", "addNotes", "canAddNotes",
                "findNotes", "findCards", "notesInfo", "cardsInfo", "cardsToNotes",
                "getDecks", "suspend", "areSuspended", "areDue", "getIntervals",
                "updateNoteFields", "getTags", "addTags", "removeTags",
                "replaceTags", "modelTemplates", "modelFieldsOnTemplates", "modelStyling",
                "get_cards", "get_due_cards", "present_card", "changeDeck", "rate_card",
                "deckStats", "collection_stats"
            )
        ))
        assertFalse("Android 不应暴露 PC GUI 占位工具", toolNames.contains("guiBrowse"))
        assertFalse("Android 不应暴露未实现的模型编辑占位工具", toolNames.contains("createModel"))
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

    @Test
    fun `pc compatible modelNames and modelFieldNames work`() = runTest {
        val namesResponse = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf("name" to "modelNames"))
        ))
        assertNull(namesResponse.error)
        assertFalse(namesResponse.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val names = Json.parseToJsonElement(
            namesResponse.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonArray.map { it.jsonPrimitive.content }
        assertTrue(names.contains("Basic"))

        val fieldsResponse = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "modelFieldNames",
                "arguments" to mapOf("modelName" to "Basic")
            ))
        ))
        assertNull(fieldsResponse.error)
        assertFalse(fieldsResponse.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val fields = Json.parseToJsonElement(
            fieldsResponse.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("Front", "Back"), fields)

        val templatesResponse = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "modelTemplates",
                "arguments" to mapOf("modelName" to "Basic")
            ))
        ))
        assertNull(templatesResponse.error)
        assertFalse(templatesResponse.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)

        val fieldsOnTemplatesResponse = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "modelFieldsOnTemplates",
                "arguments" to mapOf("modelName" to "Basic")
            ))
        ))
        assertNull(fieldsOnTemplatesResponse.error)
        assertFalse(fieldsOnTemplatesResponse.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val fieldsOnTemplates = Json.parseToJsonElement(
            fieldsOnTemplatesResponse.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        val card1 = fieldsOnTemplates["Card 1"]!!.jsonArray
        assertEquals(listOf("Front"), card1[0].jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("Back"), card1[1].jsonArray.map { it.jsonPrimitive.content })

        val stylingResponse = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "modelStyling",
                "arguments" to mapOf("modelName" to "Basic")
            ))
        ))
        assertNull(stylingResponse.error)
        assertFalse(stylingResponse.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `pc compatible deck and model tools accept common aliases`() = runTest {
        val deck = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "createDeck",
                "arguments" to mapOf("deck" to "PC Alias Deck")
            ))
        ))
        assertNull(deck.error)
        assertFalse(deck.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)

        val fields = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "modelFieldNames",
                "arguments" to mapOf("model_name" to "Basic")
            ))
        ))
        assertNull(fields.error)
        assertFalse(fields.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)

        val templates = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "modelTemplates",
                "arguments" to mapOf("model" to "Basic")
            ))
        ))
        assertFalse(templates.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)

        val fieldsOnTemplates = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "modelFieldsOnTemplates",
                "arguments" to mapOf("model" to "Basic")
            ))
        ))
        assertFalse(fieldsOnTemplates.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)

        val styling = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "modelStyling",
                "arguments" to mapOf("model_name" to "Basic")
            ))
        ))
        assertFalse(styling.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `pc compatible deck and model id aliases return AnkiConnect shapes`() = runTest {
        ankiRepo.ensureDeck("PC Id Deck")

        val deckNames = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf("name" to "deckNames"))
        ))
        assertNull(deckNames.error)
        val names = Json.parseToJsonElement(
            deckNames.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonArray.map { it.jsonPrimitive.content }
        assertTrue(names.contains("PC Id Deck"))

        val deckIds = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf("name" to "deckNamesAndIds"))
        ))
        val deckMap = Json.parseToJsonElement(
            deckIds.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertTrue(deckMap["PC Id Deck"]!!.jsonPrimitive.long > 0)

        val modelIds = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf("name" to "modelNamesAndIds"))
        ))
        val modelMap = Json.parseToJsonElement(
            modelIds.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertTrue(modelMap["Basic"]!!.jsonPrimitive.long > 0)
    }

    @Test
    fun `pc compatible addNote writes using modelName`() = runTest {
        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "addNote",
                "arguments" to mapOf(
                    "deckName" to "PC Alias",
                    "modelName" to "Basic",
                    "fields" to mapOf("Front" to "Q", "Back" to "A"),
                    "tags" to listOf("pc-alias")
                )
            ))
        ))
        assertNull(response.error)
        assertFalse(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val noteId = Json.parseToJsonElement(
            response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonPrimitive.long
        assertTrue(noteId > 0)
    }

    @Test
    fun `pc compatible addNotes batch writes using shared modelName`() = runTest {
        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "addNotes",
                "arguments" to mapOf(
                    "deckName" to "PC Batch Alias",
                    "modelName" to "Basic",
                    "notes" to listOf(
                        mapOf("fields" to mapOf("Front" to "Q1", "Back" to "A1")),
                        mapOf("fields" to mapOf("Front" to "Q2", "Back" to "A2"), "tags" to listOf("one"))
                    ),
                    "tags" to listOf("shared")
                )
            ))
        ))
        assertNull(response.error)
        assertFalse(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val noteIds = Json.parseToJsonElement(
            response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonArray
        assertEquals(2, noteIds.size)
        assertTrue(noteIds.all { it.jsonPrimitive.long > 0 })
    }

    @Test
    fun `pc compatible addNotes accepts AnkiConnect per-note deck and model shape`() = runTest {
        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "addNotes",
                "arguments" to mapOf(
                    "notes" to listOf(
                        mapOf(
                            "deckName" to "PC Per Note A",
                            "modelName" to "Basic",
                            "fields" to mapOf("Front" to "Per note Q1", "Back" to "Per note A1")
                        ),
                        mapOf(
                            "deckName" to "PC Per Note B",
                            "modelName" to "Basic",
                            "fields" to mapOf("Front" to "Per note Q2", "Back" to "Per note A2"),
                            "tags" to listOf("per-note")
                        )
                    )
                )
            ))
        ))
        assertNull(response.error)
        assertFalse(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val noteIds = Json.parseToJsonElement(
            response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonArray
        assertEquals(2, noteIds.size)
        assertTrue(noteIds.all { it.jsonPrimitive.long > 0 })

        val decks = ankiRepo.listDecks().map { it.name }
        assertTrue(decks.contains("PC Per Note A"))
        assertTrue(decks.contains("PC Per Note B"))
    }

    @Test
    fun `pc compatible canAddNotes validates candidates without writing`() = runTest {
        parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "addNote",
                "arguments" to mapOf(
                    "deckName" to "PC Can Add",
                    "modelName" to "Basic",
                    "fields" to mapOf("Front" to "Existing can-add front", "Back" to "A")
                )
            ))
        )).also { assertFalse(it.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean) }

        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "canAddNotes",
                "arguments" to mapOf(
                    "deckName" to "PC Can Add",
                    "modelName" to "Basic",
                    "notes" to listOf(
                        mapOf("fields" to mapOf("Front" to "Fresh can-add front", "Back" to "A")),
                        mapOf("fields" to mapOf("Front" to "Existing can-add front", "Back" to "Duplicate")),
                        mapOf("fields" to mapOf("Front" to "Existing can-add front", "Back" to "Allowed"), "allowDuplicate" to true),
                        mapOf("fields" to mapOf("Front" to "Bad field front", "Missing" to "Nope")),
                        mapOf("fields" to mapOf("Front" to "Media front", "Back" to "A"), "audio" to listOf(mapOf("url" to "https://example.com/a.mp3")))
                    )
                )
            ))
        ))
        assertNull(response.error)
        assertFalse(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val result = Json.parseToJsonElement(
            response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonArray.map { it.jsonPrimitive.boolean }
        assertEquals(listOf(true, false, true, false, false), result)
        assertEquals(1, ankiRepo.noteCount())
    }

    @Test
    fun `pc compatible addNote skips duplicates unless explicitly allowed`() = runTest {
        val first = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "addNote",
                "arguments" to mapOf(
                    "deckName" to "PC Duplicates",
                    "modelName" to "Basic",
                    "fields" to mapOf("Front" to "Same front", "Back" to "A")
                )
            ))
        ))
        assertTrue(Json.parseToJsonElement(
            first.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonPrimitive.long > 0)

        val duplicate = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "addNote",
                "arguments" to mapOf(
                    "deckName" to "PC Duplicates",
                    "modelName" to "Basic",
                    "fields" to mapOf("Front" to "Same front", "Back" to "B")
                )
            ))
        ))
        assertTrue(Json.parseToJsonElement(
            duplicate.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ) is kotlinx.serialization.json.JsonNull)

        val allowed = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "addNote",
                "arguments" to mapOf(
                    "deckName" to "PC Duplicates",
                    "modelName" to "Basic",
                    "fields" to mapOf("Front" to "Same front", "Back" to "C"),
                    "allowDuplicate" to true
                )
            ))
        ))
        assertTrue(Json.parseToJsonElement(
            allowed.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonPrimitive.long > 0)
    }

    @Test
    fun `pc compatible addNote accepts wrapped note options and snake case names`() = runTest {
        parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "addNote",
                "arguments" to mapOf(
                    "note" to mapOf(
                        "deck_name" to "PC Wrapped",
                        "model_name" to "Basic",
                        "fields" to mapOf("Front" to "Wrapped front", "Back" to "A")
                    )
                )
            ))
        )).also { assertFalse(it.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean) }

        val duplicateAllowed = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "addNote",
                "arguments" to mapOf(
                    "note" to mapOf(
                        "deck_name" to "PC Wrapped",
                        "model_name" to "Basic",
                        "fields" to mapOf("Front" to "Wrapped front", "Back" to "B"),
                        "options" to mapOf("allowDuplicate" to true)
                    )
                )
            ))
        ))
        assertFalse(duplicateAllowed.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        assertTrue(Json.parseToJsonElement(
            duplicateAllowed.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonPrimitive.long > 0)
    }

    @Test
    fun `pc compatible addNote rejects unsupported media attachments`() = runTest {
        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "addNote",
                "arguments" to mapOf(
                    "note" to mapOf(
                        "deckName" to "PC Media",
                        "modelName" to "Basic",
                        "fields" to mapOf("Front" to "Media front", "Back" to "A"),
                        "audio" to listOf(mapOf("url" to "https://example.com/a.mp3"))
                    )
                )
            ))
        ))
        assertNull(response.error)
        assertTrue(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val error = Json.parseToJsonElement(
            response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertEquals(BusinessErrorCodes.INVALID_ARGUMENT, error["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pc compatible note search info tag and update flow works`() = runTest {
        val add = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "addNote",
                "arguments" to mapOf(
                    "deckName" to "PC Flow",
                    "modelName" to "Basic",
                    "fields" to mapOf("Front" to "Original front", "Back" to "Original back"),
                    "tags" to listOf("flow-start")
                )
            ))
        ))
        val noteId = Json.parseToJsonElement(
            add.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonPrimitive.long

        val find = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf("name" to "findNotes", "arguments" to mapOf("query" to "Original front")))
        ))
        val foundIds = Json.parseToJsonElement(
            find.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonArray.map { it.jsonPrimitive.long }
        assertTrue(foundIds.contains(noteId))

        parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "addTags",
                "arguments" to mapOf("notes" to listOf(noteId), "tags" to "flow-added")
            ))
        )).also { assertFalse(it.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean) }

        val tags = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf("name" to "getTags", "arguments" to mapOf("pattern" to "flow")))
        ))
        val tagNames = Json.parseToJsonElement(
            tags.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonArray.map { it.jsonPrimitive.content }
        assertTrue(tagNames.contains("flow-added"))

        parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "replaceTags",
                "arguments" to mapOf("notes" to listOf(noteId), "tagToReplace" to "flow-added", "replaceWithTag" to "flow-renamed")
            ))
        )).also { assertFalse(it.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean) }

        val info = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf("name" to "notesInfo", "arguments" to mapOf("notes" to listOf(noteId))))
        ))
        val noteInfo = Json.parseToJsonElement(
            info.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonArray[0].jsonObject
        assertEquals("Basic", noteInfo["modelName"]!!.jsonPrimitive.content)
        assertEquals("Original front", noteInfo["fields"]!!.jsonObject["Front"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals(0, noteInfo["fields"]!!.jsonObject["Front"]!!.jsonObject["order"]!!.jsonPrimitive.int)

        val update = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "updateNoteFields",
                "arguments" to mapOf("note" to mapOf("id" to noteId, "fields" to mapOf("Front" to "Updated front")))
            ))
        ))
        assertFalse(update.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)

        parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "removeTags",
                "arguments" to mapOf("notes" to listOf(noteId), "tags" to "flow-renamed")
            ))
        )).also { assertFalse(it.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean) }
    }

    @Test
    fun `pc compatible tag replacement aliases and video rejection work`() = runTest {
        val add = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "addNote",
                "arguments" to mapOf(
                    "deckName" to "PC Alias Flow",
                    "modelName" to "Basic",
                    "fields" to mapOf("Front" to "Alias front", "Back" to "Alias back"),
                    "tags" to listOf("alias-start")
                )
            ))
        ))
        val noteId = Json.parseToJsonElement(
            add.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonPrimitive.long

        parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "replaceTags",
                "arguments" to mapOf("notes" to listOf(noteId), "tag_to_replace" to "alias-start", "replace_with_tag" to "alias-renamed")
            ))
        )).also { assertFalse(it.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean) }

        val videoUpdate = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "updateNoteFields",
                "arguments" to mapOf(
                    "note" to mapOf(
                        "id" to noteId,
                        "fields" to mapOf("Front" to "Alias updated"),
                        "video" to listOf(mapOf("url" to "https://example.com/v.mp4"))
                    )
                )
            ))
        ))
        assertNull(videoUpdate.error)
        assertTrue(videoUpdate.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `pc compatible card retrieval present change deck and rate flow works`() = runTest {
        val add = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "addNote",
                "arguments" to mapOf(
                    "deckName" to "PC Cards",
                    "modelName" to "Basic",
                    "fields" to mapOf("Front" to "Card front", "Back" to "Card back"),
                    "allowDuplicate" to true
                )
            ))
        ))
        val noteId = Json.parseToJsonElement(
            add.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonPrimitive.long
        val cardId = noteId * 10

        val cards = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "get_cards",
                "arguments" to mapOf("deck_name" to "PC Cards", "card_state" to "due", "limit" to 10)
            ))
        ))
        val cardList = Json.parseToJsonElement(
            cards.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonArray
        assertTrue(cardList.any { it.jsonObject["cardId"]!!.jsonPrimitive.long == cardId })

        val foundCards = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "findCards",
                "arguments" to mapOf("query" to "Card front")
            ))
        ))
        val foundCardIds = Json.parseToJsonElement(
            foundCards.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonArray.map { it.jsonPrimitive.long }
        assertTrue(foundCardIds.contains(cardId))

        val cardInfo = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "cardsInfo",
                "arguments" to mapOf("cards" to listOf(cardId))
            ))
        ))
        val cardInfoList = Json.parseToJsonElement(
            cardInfo.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonArray
        assertEquals(noteId, cardInfoList[0].jsonObject["noteId"]!!.jsonPrimitive.long)

        val cardsToNotes = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "cardsToNotes",
                "arguments" to mapOf("cards" to listOf(cardId))
            ))
        ))
        val mappedNoteIds = Json.parseToJsonElement(
            cardsToNotes.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonArray.map { it.jsonPrimitive.long }
        assertEquals(listOf(noteId), mappedNoteIds)

        val mappedDecks = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "getDecks",
                "arguments" to mapOf("cards" to listOf(cardId))
            ))
        ))
        val deckMap = Json.parseToJsonElement(
            mappedDecks.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertEquals(cardId, deckMap["PC Cards"]!!.jsonArray[0].jsonPrimitive.long)

        val dueBeforeSuspend = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "areDue",
                "arguments" to mapOf("cards" to listOf(cardId))
            ))
        ))
        assertTrue(Json.parseToJsonElement(
            dueBeforeSuspend.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonArray[0].jsonPrimitive.boolean)

        val intervals = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "getIntervals",
                "arguments" to mapOf("cards" to listOf(cardId))
            ))
        ))
        assertEquals(1, Json.parseToJsonElement(
            intervals.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonArray[0].jsonPrimitive.int)

        val completeIntervals = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "getIntervals",
                "arguments" to mapOf("cards" to listOf(cardId), "complete" to true)
            ))
        ))
        assertTrue(completeIntervals.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)

        val suspend = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "suspend",
                "arguments" to mapOf("cards" to listOf(cardId))
            ))
        ))
        assertTrue(Json.parseToJsonElement(
            suspend.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonPrimitive.boolean)

        val suspended = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "areSuspended",
                "arguments" to mapOf("cards" to listOf(cardId))
            ))
        ))
        assertTrue(Json.parseToJsonElement(
            suspended.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonArray[0].jsonPrimitive.boolean)

        val presentQuestion = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "present_card",
                "arguments" to mapOf("card_id" to cardId, "show_answer" to false)
            ))
        ))
        val question = Json.parseToJsonElement(
            presentQuestion.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertEquals("Card front", question["question"]!!.jsonPrimitive.content)
        assertFalse(question.containsKey("answer"))

        parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "changeDeck",
                "arguments" to mapOf("cards" to listOf(cardId), "deck" to "PC Cards Moved")
            ))
        )).also { assertFalse(it.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean) }

        val rate = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "rate_card",
                "arguments" to mapOf("card_id" to cardId, "rating" to 3, "time_taken_ms" to 1200)
            ))
        ))
        assertFalse(rate.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)

        val stats = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "deckStats",
                "arguments" to mapOf("deck" to "PC Cards Moved")
            ))
        ))
        val deckStats = Json.parseToJsonElement(
            stats.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertEquals(1, deckStats["counts"]!!.jsonObject["total"]!!.jsonPrimitive.int)

        val collection = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf("name" to "collection_stats"))
        ))
        assertFalse(collection.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `pc compatible card tools accept camel case aliases`() = runTest {
        val add = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "addNote",
                "arguments" to mapOf(
                    "deckName" to "PC Camel Cards",
                    "modelName" to "Basic",
                    "fields" to mapOf("Front" to "Camel card front", "Back" to "Camel card back"),
                    "allowDuplicate" to true
                )
            ))
        ))
        val noteId = Json.parseToJsonElement(
            add.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonPrimitive.long
        val cardId = noteId * 10

        val cards = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "get_cards",
                "arguments" to mapOf("deckName" to "PC Camel Cards", "cardState" to "due", "limit" to 10)
            ))
        ))
        assertFalse(cards.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)

        val present = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "present_card",
                "arguments" to mapOf("cardId" to cardId, "showAnswer" to true)
            ))
        ))
        val card = Json.parseToJsonElement(
            present.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertEquals("Camel card back", card["answer"]!!.jsonPrimitive.content)

        val due = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "get_due_cards",
                "arguments" to mapOf("deckName" to "PC Camel Cards", "limit" to 10)
            ))
        ))
        assertFalse(due.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)

        val rate = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "rate_card",
                "arguments" to mapOf("cardId" to cardId, "rating" to 2, "timeTakenMs" to 900)
            ))
        ))
        assertFalse(rate.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
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

    // ─── v0.2.2：空 deck 校验 / 自动创建牌组 / deckCreated 上报 ───

    @Test
    fun `tools call add_note empty deck returns DECK_NAME_EMPTY`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))
        val basicId = basicNoteTypeId()
        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "add_note",
                "arguments" to mapOf(
                    "deck" to "",
                    "noteTypeId" to basicId,
                    "fields" to mapOf("Front" to "Q", "Back" to "A")
                )
            ))
        ))
        assertDeckNameEmpty(response)
    }

    @Test
    fun `tools call add_note whitespace deck returns DECK_NAME_EMPTY`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))
        val basicId = basicNoteTypeId()
        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "add_note",
                "arguments" to mapOf(
                    "deck" to "   ",
                    "noteTypeId" to basicId,
                    "fields" to mapOf("Front" to "Q", "Back" to "A")
                )
            ))
        ))
        assertDeckNameEmpty(response)
    }

    @Test
    fun `tools call add_notes empty deck returns DECK_NAME_EMPTY`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))
        val basicId = basicNoteTypeId()
        val notes = listOf(mapOf("noteTypeId" to basicId, "fields" to mapOf("Front" to "Q", "Back" to "A")))
        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "add_notes",
                "arguments" to mapOf("deck" to "", "notes" to notes)
            ))
        ))
        assertDeckNameEmpty(response)
    }

    @Test
    fun `tools call add_notes whitespace deck returns DECK_NAME_EMPTY`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))
        val basicId = basicNoteTypeId()
        val notes = listOf(mapOf("noteTypeId" to basicId, "fields" to mapOf("Front" to "Q", "Back" to "A")))
        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "add_notes",
                "arguments" to mapOf("deck" to "   ", "notes" to notes)
            ))
        ))
        assertDeckNameEmpty(response)
    }

    @Test
    fun `tools call add_basic_note empty deck returns DECK_NAME_EMPTY`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))
        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "add_basic_note",
                "arguments" to mapOf("deck" to "", "front" to "Q", "back" to "A")
            ))
        ))
        assertDeckNameEmpty(response)
    }

    @Test
    fun `tools call add_basic_note whitespace deck returns DECK_NAME_EMPTY`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))
        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "add_basic_note",
                "arguments" to mapOf("deck" to "   ", "front" to "Q", "back" to "A")
            ))
        ))
        assertDeckNameEmpty(response)
    }

    @Test
    fun `tools call add_basic_notes empty deck returns DECK_NAME_EMPTY`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))
        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "add_basic_notes",
                "arguments" to mapOf("deck" to "", "notes" to listOf(mapOf("front" to "Q", "back" to "A")))
            ))
        ))
        assertDeckNameEmpty(response)
    }

    @Test
    fun `tools call add_basic_notes whitespace deck returns DECK_NAME_EMPTY`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))
        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "add_basic_notes",
                "arguments" to mapOf("deck" to "   ", "notes" to listOf(mapOf("front" to "Q", "back" to "A")))
            ))
        ))
        assertDeckNameEmpty(response)
    }

    @Test
    fun `tools call add_note auto-creates new deck and reports deckCreated true`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))
        val basicId = basicNoteTypeId()
        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "add_note",
                "arguments" to mapOf(
                    "deck" to "新牌组_自动创建",
                    "noteTypeId" to basicId,
                    "fields" to mapOf("Front" to "Q", "Back" to "A")
                )
            ))
        ))
        assertNull(response.error)
        assertFalse(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val json = Json.parseToJsonElement(
            response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertTrue(json["success"]!!.jsonPrimitive.boolean)
        assertTrue(json["deckCreated"]!!.jsonPrimitive.boolean)
        assertTrue(json["deckId"]!!.jsonPrimitive.long > 0)
    }

    @Test
    fun `tools call add_note reuses existing deck and reports deckCreated false`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))
        val basicId = basicNoteTypeId()

        val first = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "add_note",
                "arguments" to mapOf(
                    "deck" to "复用牌组",
                    "noteTypeId" to basicId,
                    "fields" to mapOf("Front" to "Q1", "Back" to "A1")
                )
            ))
        ))
        val firstJson = Json.parseToJsonElement(
            first.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertTrue(firstJson["deckCreated"]!!.jsonPrimitive.boolean)
        val deckId = firstJson["deckId"]!!.jsonPrimitive.long

        val second = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "add_note",
                "arguments" to mapOf(
                    "deck" to "复用牌组",
                    "noteTypeId" to basicId,
                    "fields" to mapOf("Front" to "Q2", "Back" to "A2")
                )
            ))
        ))
        val secondJson = Json.parseToJsonElement(
            second.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertFalse(secondJson["deckCreated"]!!.jsonPrimitive.boolean)
        assertEquals(deckId, secondJson["deckId"]!!.jsonPrimitive.long)
    }

    @Test
    fun `tools call add_notes auto-creates new deck and reports deckCreated true`() = runTest {
        handler.handleRequest(buildRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf<String, Any>()
        )))
        val basicId = basicNoteTypeId()
        val notes = listOf(
            mapOf("noteTypeId" to basicId, "fields" to mapOf("Front" to "Q1", "Back" to "A1")),
            mapOf("noteTypeId" to basicId, "fields" to mapOf("Front" to "Q2", "Back" to "A2"))
        )
        val response = parseResponse(handler.handleRequest(
            buildRequest("tools/call", mapOf(
                "name" to "add_notes",
                "arguments" to mapOf("deck" to "批量新牌组", "notes" to notes)
            ))
        ))
        assertNull(response.error)
        assertFalse(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val json = Json.parseToJsonElement(
            response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertEquals(2, json["succeeded"]!!.jsonPrimitive.int)
        assertTrue(json["deckCreated"]!!.jsonPrimitive.boolean)
        assertTrue(json["deckId"]!!.jsonPrimitive.long > 0)
    }

    @Test
    fun `add_note inputSchema deck enforces minLength 1`() = runTest {
        val tools = parseResponse(handler.handleRequest(buildRequest("tools/list")))
            .result!!.jsonObject["tools"]!!.jsonArray
        val addNote = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "add_note" }.jsonObject
        val deckProp = addNote["inputSchema"]!!.jsonObject["properties"]!!.jsonObject["deck"]!!.jsonObject
        assertEquals(1, deckProp["minLength"]!!.jsonPrimitive.int)
    }

    @Test
    fun `add_note description documents auto-create deck and statelessness`() = runTest {
        val tools = parseResponse(handler.handleRequest(buildRequest("tools/list")))
            .result!!.jsonObject["tools"]!!.jsonArray
        val desc = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "add_note" }
            .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue("add_note 描述应说明牌组会自动创建", desc.contains("自动创建"))
        assertTrue("add_note 描述应说明工具无状态、不继承当前牌组", desc.contains("无状态"))
    }

    @Test
    fun `ensure_deck description documents no state inheritance`() = runTest {
        val tools = parseResponse(handler.handleRequest(buildRequest("tools/list")))
            .result!!.jsonObject["tools"]!!.jsonArray
        val desc = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "ensure_deck" }
            .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue("ensure_deck 描述应说明不会为后续调用设置当前牌组", desc.contains("无状态"))
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
                        is Map<*, *> -> mapToJsonElement(v.toStringAnyMap())
                        is String -> JsonPrimitive(v)
                        else -> JsonPrimitive(v.toString())
                    }
                })
                is Map<*, *> -> mapToJsonElement(value.toStringAnyMap())
                else -> JsonPrimitive(value.toString())
            }
        }
        return JsonObject(entries.toMap())
    }

    private fun Map<*, *>.toStringAnyMap(): Map<String, Any> =
        entries.associate { (key, value) -> key.toString() to (value ?: "") }

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

    private fun basicNoteTypeId(): Long {
        val listJson = Json.parseToJsonElement(
            parseResponse(handler.handleRequest(buildRequest("tools/call", mapOf("name" to "list_note_types"))))
                .result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        return listJson["noteTypes"]!!.jsonArray.first {
            it.jsonObject["name"]!!.jsonPrimitive.content == "Basic"
        }.jsonObject["id"]!!.jsonPrimitive.long
    }

    private fun assertDeckNameEmpty(response: JsonRpcResponse) {
        assertNull(response.error)
        assertTrue(response.result!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val errJson = Json.parseToJsonElement(
            response.result!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
        assertEquals(BusinessErrorCodes.DECK_NAME_EMPTY, errJson["code"]?.jsonPrimitive?.content)
    }
}
