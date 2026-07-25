package xyz.chenmilin.ankimcpbridge.server.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.chenmilin.ankimcpbridge.anki.AddGenericNoteRequest
import xyz.chenmilin.ankimcpbridge.anki.AddGenericNotesRequest
import xyz.chenmilin.ankimcpbridge.anki.AnkiDroidNotInstalledException
import xyz.chenmilin.ankimcpbridge.anki.AnkiPermissionDeniedException
import xyz.chenmilin.ankimcpbridge.anki.AnkiRepository
import xyz.chenmilin.ankimcpbridge.anki.BatchError
import xyz.chenmilin.ankimcpbridge.anki.FieldMappingException
import xyz.chenmilin.ankimcpbridge.anki.GenericNoteItem
import xyz.chenmilin.ankimcpbridge.anki.ModelNotFoundException
import xyz.chenmilin.ankimcpbridge.server.BusinessErrorCodes
import xyz.chenmilin.ankimcpbridge.server.McpTool
import xyz.chenmilin.ankimcpbridge.server.McpToolCallResult
import xyz.chenmilin.ankimcpbridge.server.McpToolContent
import xyz.chenmilin.ankimcpbridge.server.McpToolDef

private fun emptySchema(): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(emptyMap()),
        "required" to JsonArray(emptyList())
    )
)

private fun stringArray(values: List<String>): JsonArray =
    JsonArray(values.map { JsonPrimitive(it) })

private fun pcBusinessError(code: String, message: String): McpToolCallResult =
    businessError(code, message)

private fun pcExceptionError(e: Exception): McpToolCallResult =
    when (e) {
        is ToolErrorException -> pcBusinessError(e.errorCode, e.message)
        is AnkiDroidNotInstalledException -> pcBusinessError(BusinessErrorCodes.ANKIDROID_NOT_INSTALLED, e.message ?: "AnkiDroid 未安装")
        is AnkiPermissionDeniedException -> pcBusinessError(BusinessErrorCodes.ANKI_PERMISSION_DENIED, e.message ?: "AnkiDroid 权限未授权")
        is ModelNotFoundException -> pcBusinessError(BusinessErrorCodes.NOTE_TYPE_NOT_FOUND, e.message ?: "笔记类型未找到")
        is FieldMappingException -> pcBusinessError(e.code, e.message ?: "字段映射失败")
        else -> pcBusinessError(BusinessErrorCodes.INTERNAL_ERROR, e.message ?: "内部错误")
    }

private suspend fun findNoteTypeIdByName(ankiRepository: AnkiRepository, modelName: String): Long {
    val matches = ankiRepository.listNoteTypes().filter { it.name.equals(modelName, ignoreCase = true) }
    if (matches.isEmpty()) throw ModelNotFoundException("笔记类型不存在: $modelName")
    if (matches.size > 1) {
        throw ToolErrorException(BusinessErrorCodes.NOTE_TYPE_AMBIGUOUS, "笔记类型名称不唯一: $modelName")
    }
    return matches.first().id
}

class PcListDecksTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "listDecks",
        description = "PC Anki MCP compatible alias: list AnkiDroid deck names. includeStats is accepted but Android deck statistics are not available through the public ContentProvider API.",
        inputSchema = emptySchema()
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult = try {
        val decks = ankiRepository.listDecks()
        val result = JsonObject(
            mapOf(
                "decks" to JsonArray(decks.map { deck ->
                    JsonObject(mapOf("id" to JsonPrimitive(deck.id), "name" to JsonPrimitive(deck.name)))
                }),
                "deckNames" to stringArray(decks.map { it.name }),
                "count" to JsonPrimitive(decks.size),
                "statsAvailable" to JsonPrimitive(false)
            )
        )
        McpToolCallResult(listOf(McpToolContent(text = result.toString())))
    } catch (e: Exception) {
        pcExceptionError(e)
    }
}

class PcCreateDeckTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "createDeck",
        description = "PC Anki MCP compatible alias: create an AnkiDroid deck if it does not already exist.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf("deckName" to JsonObject(mapOf("type" to JsonPrimitive("string"), "minLength" to JsonPrimitive(1))))
                ),
                "required" to JsonArray(listOf(JsonPrimitive("deckName")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val deckName = arguments?.get("deckName")?.jsonPrimitive?.content
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: deckName")
        if (deckName.isBlank()) return pcBusinessError(BusinessErrorCodes.DECK_NAME_EMPTY, "deckName 不能为空")
        return try {
            val deck = ankiRepository.ensureDeck(deckName)
            val result = JsonObject(
                mapOf(
                    "id" to JsonPrimitive(deck.id),
                    "name" to JsonPrimitive(deck.name),
                    "created" to JsonPrimitive(deck.created)
                )
            )
            McpToolCallResult(listOf(McpToolContent(text = result.toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcModelNamesTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "modelNames",
        description = "PC Anki MCP compatible alias: list available AnkiDroid note type names.",
        inputSchema = emptySchema()
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult = try {
        val names = ankiRepository.listNoteTypes().map { it.name }
        McpToolCallResult(listOf(McpToolContent(text = stringArray(names).toString())))
    } catch (e: Exception) {
        pcExceptionError(e)
    }
}

class PcModelFieldNamesTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "modelFieldNames",
        description = "PC Anki MCP compatible alias: get ordered field names for a note type by modelName.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf("modelName" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
                "required" to JsonArray(listOf(JsonPrimitive("modelName")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val modelName = arguments?.get("modelName")?.jsonPrimitive?.content
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: modelName")
        return try {
            val id = findNoteTypeIdByName(ankiRepository, modelName)
            val detail = ankiRepository.getNoteType(id)
            McpToolCallResult(listOf(McpToolContent(text = stringArray(detail.fields).toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcAddNoteTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "addNote",
        description = "PC Anki MCP compatible alias: add one note using deckName, modelName, fields and optional tags.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "deckName" to JsonObject(mapOf("type" to JsonPrimitive("string"), "minLength" to JsonPrimitive(1))),
                        "modelName" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "fields" to JsonObject(mapOf("type" to JsonPrimitive("object"))),
                        "tags" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))))
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("deckName"), JsonPrimitive("modelName"), JsonPrimitive("fields")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val deckName = arguments?.get("deckName")?.jsonPrimitive?.content
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: deckName")
        if (deckName.isBlank()) return pcBusinessError(BusinessErrorCodes.DECK_NAME_EMPTY, "deckName 不能为空")
        val modelName = arguments["modelName"]?.jsonPrimitive?.content
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: modelName")
        val fields = arguments["fields"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: fields")
        val tags = arguments["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.trim().takeIf { s -> s.isNotBlank() } } ?: emptyList()
        return try {
            val noteTypeId = findNoteTypeIdByName(ankiRepository, modelName)
            val result = ankiRepository.addNote(
                AddGenericNoteRequest(deck = deckName.trim(), noteTypeId = noteTypeId, fields = fields, tags = tags)
            )
            val json = JsonObject(
                mapOf(
                    "noteId" to (result.noteId?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull),
                    "success" to JsonPrimitive(result.success),
                    "deck" to JsonPrimitive(result.deck),
                    "modelName" to JsonPrimitive(modelName),
                    "noteTypeId" to JsonPrimitive(noteTypeId),
                    "persisted" to JsonPrimitive(result.persisted),
                    "refreshNotified" to JsonPrimitive(result.refreshNotified)
                )
            )
            McpToolCallResult(listOf(McpToolContent(text = json.toString())), isError = !result.success)
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcAddNotesTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "addNotes",
        description = "PC Anki MCP compatible alias: add up to 100 notes sharing deckName and modelName.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "deckName" to JsonObject(mapOf("type" to JsonPrimitive("string"), "minLength" to JsonPrimitive(1))),
                        "modelName" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "notes" to JsonObject(mapOf("type" to JsonPrimitive("array"))),
                        "tags" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))))
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("deckName"), JsonPrimitive("modelName"), JsonPrimitive("notes")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val deckName = arguments?.get("deckName")?.jsonPrimitive?.content
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: deckName")
        if (deckName.isBlank()) return pcBusinessError(BusinessErrorCodes.DECK_NAME_EMPTY, "deckName 不能为空")
        val modelName = arguments["modelName"]?.jsonPrimitive?.content
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: modelName")
        val notesArray = arguments["notes"]?.jsonArray
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: notes")
        if (notesArray.isEmpty()) return pcBusinessError(BusinessErrorCodes.INVALID_ARGUMENT, "notes 不能为空")
        if (notesArray.size > 100) return pcBusinessError(BusinessErrorCodes.BATCH_TOO_LARGE, "一次最多添加 100 张笔记")
        val sharedTags = arguments["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.trim().takeIf { s -> s.isNotBlank() } } ?: emptyList()
        return try {
            val noteTypeId = findNoteTypeIdByName(ankiRepository, modelName)
            val notes = notesArray.map { element ->
                val obj = element.jsonObject
                val fields = obj["fields"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }
                    ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "notes 每项都必须包含 fields")
                val noteTags = obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.trim().takeIf { s -> s.isNotBlank() } } ?: emptyList()
                GenericNoteItem(noteTypeId = noteTypeId, fields = fields, tags = (sharedTags + noteTags).distinct())
            }
            val result = ankiRepository.addNotes(AddGenericNotesRequest(deck = deckName.trim(), notes = notes))
            val json = JsonObject(
                mapOf(
                    "requested" to JsonPrimitive(result.requested),
                    "submitted" to JsonPrimitive(result.submitted),
                    "succeeded" to JsonPrimitive(result.succeeded),
                    "failed" to JsonPrimitive(result.failed),
                    "noteIds" to JsonArray(result.noteIds.map { JsonPrimitive(it) }),
                    "noteIdsAvailable" to JsonPrimitive(result.noteIdsAvailable),
                    "persisted" to JsonPrimitive(result.persisted),
                    "refreshNotified" to JsonPrimitive(result.refreshNotified),
                    "errors" to JsonArray(result.errors.map { it.toJson() })
                )
            )
            McpToolCallResult(listOf(McpToolContent(text = json.toString())), isError = result.failed > 0 || !result.persisted)
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }

    private fun BatchError.toJson(): JsonObject = JsonObject(
        mapOf(
            "index" to JsonPrimitive(index),
            "code" to JsonPrimitive(code),
            "message" to JsonPrimitive(message)
        )
    )
}

class PcFindNotesTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "findNotes",
        description = "PC Anki MCP compatible: search notes with Anki browser query syntax supported by AnkiDroid.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf("query" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
                "required" to JsonArray(listOf(JsonPrimitive("query")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val query = arguments?.get("query")?.jsonPrimitive?.content
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: query")
        return try {
            val ids = ankiRepository.findNotes(query)
            McpToolCallResult(listOf(McpToolContent(text = JsonArray(ids.map { JsonPrimitive(it) }).toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcNotesInfoTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "notesInfo",
        description = "PC Anki MCP compatible: get note fields, tags and model info for up to 100 notes.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf("notes" to JsonObject(mapOf("type" to JsonPrimitive("array"))))),
                "required" to JsonArray(listOf(JsonPrimitive("notes")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val ids = arguments?.get("notes")?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: notes")
        return try {
            val infos = ankiRepository.notesInfo(ids)
            val json = JsonArray(infos.map { info ->
                JsonObject(
                    mapOf(
                        "noteId" to JsonPrimitive(info.id),
                        "modelName" to JsonPrimitive(info.modelName),
                        "noteTypeId" to JsonPrimitive(info.noteTypeId),
                        "fields" to JsonObject(info.fields.mapValues { JsonPrimitive(it.value) }),
                        "tags" to stringArray(info.tags)
                    )
                )
            })
            McpToolCallResult(listOf(McpToolContent(text = json.toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcUpdateNoteFieldsTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "updateNoteFields",
        description = "PC Anki MCP compatible: update fields of one existing note.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "note" to JsonObject(mapOf("type" to JsonPrimitive("object")))
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("note")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val note = arguments?.get("note")?.jsonObject
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: note")
        val id = note["id"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少或非法参数: note.id")
        if (note["audio"] != null || note["picture"] != null) {
            return pcBusinessError(BusinessErrorCodes.INVALID_ARGUMENT, "安卓版 updateNoteFields 只支持 fields 字段更新，不支持媒体附件更新")
        }
        val fields = note["fields"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: note.fields")
        return try {
            val updated = ankiRepository.updateNoteFields(id, fields)
            McpToolCallResult(listOf(McpToolContent(text = JsonObject(mapOf("updated" to JsonPrimitive(updated))).toString())), isError = !updated)
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcGetTagsTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "getTags",
        description = "PC Anki MCP compatible: list tags, optionally filtered by substring.",
        inputSchema = emptySchema()
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult = try {
        val pattern = arguments?.get("pattern")?.jsonPrimitive?.content
        McpToolCallResult(listOf(McpToolContent(text = stringArray(ankiRepository.getTags(pattern)).toString())))
    } catch (e: Exception) {
        pcExceptionError(e)
    }
}

abstract class PcTagMutationTool(
    private val ankiRepository: AnkiRepository,
    private val toolName: String,
    private val add: Boolean
) : McpTool {
    override val definition = McpToolDef(
        name = toolName,
        description = "PC Anki MCP compatible: ${if (add) "add" else "remove"} space-separated tags on notes.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "notes" to JsonObject(mapOf("type" to JsonPrimitive("array"))),
                        "tags" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("notes"), JsonPrimitive("tags")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val ids = arguments?.get("notes")?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: notes")
        val tags = arguments["tags"]?.jsonPrimitive?.content?.split(" ")?.filter { it.isNotBlank() }
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: tags")
        return try {
            val updated = if (add) ankiRepository.addTags(ids, tags) else ankiRepository.removeTags(ids, tags)
            McpToolCallResult(listOf(McpToolContent(text = JsonObject(mapOf("updated" to JsonPrimitive(updated))).toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcAddTagsTool(ankiRepository: AnkiRepository) : PcTagMutationTool(ankiRepository, "addTags", add = true)

class PcRemoveTagsTool(ankiRepository: AnkiRepository) : PcTagMutationTool(ankiRepository, "removeTags", add = false)
