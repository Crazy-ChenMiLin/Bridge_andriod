package xyz.chenmilin.ankimcpbridge.server.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.chenmilin.ankimcpbridge.anki.AddGenericNoteRequest
import xyz.chenmilin.ankimcpbridge.anki.AnkiCardInfo
import xyz.chenmilin.ankimcpbridge.anki.AnkiDroidNotInstalledException
import xyz.chenmilin.ankimcpbridge.anki.AnkiPermissionDeniedException
import xyz.chenmilin.ankimcpbridge.anki.AnkiNoteTypeDetail
import xyz.chenmilin.ankimcpbridge.anki.AnkiRepository
import xyz.chenmilin.ankimcpbridge.anki.FieldMappingException
import xyz.chenmilin.ankimcpbridge.anki.mapNoteFields
import xyz.chenmilin.ankimcpbridge.anki.ModelNotFoundException
import xyz.chenmilin.ankimcpbridge.server.BusinessErrorCodes
import xyz.chenmilin.ankimcpbridge.server.McpTool
import xyz.chenmilin.ankimcpbridge.server.McpToolCallResult
import xyz.chenmilin.ankimcpbridge.server.McpToolContent
import xyz.chenmilin.ankimcpbridge.server.McpToolDef
import xyz.chenmilin.ankimcpbridge.server.ToolRegistry

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

private suspend fun findNoteTypeByName(ankiRepository: AnkiRepository, modelName: String): AnkiNoteTypeDetail =
    ankiRepository.getNoteType(findNoteTypeIdByName(ankiRepository, modelName))

private fun JsonObject.stringList(name: String): List<String> =
    this[name]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.trim().takeIf { s -> s.isNotBlank() } } ?: emptyList()

private fun JsonObject.stringValue(vararg names: String): String? =
    names.firstNotNullOfOrNull { name -> this[name]?.jsonPrimitive?.content }

private fun JsonObject.booleanValue(vararg names: String): Boolean? =
    names.firstNotNullOfOrNull { name -> this[name]?.jsonPrimitive?.booleanOrNull }

private fun JsonObject.longValue(vararg names: String): Long? =
    names.firstNotNullOfOrNull { name -> this[name]?.jsonPrimitive?.content?.toLongOrNull() }

private fun JsonObject.intValue(vararg names: String): Int? =
    names.firstNotNullOfOrNull { name -> this[name]?.jsonPrimitive?.content?.toIntOrNull() }

private fun JsonObject.unsupportedAttachmentNames(): List<String> =
    listOf("audio", "picture", "video").filter { this[it] != null }

private fun jsonLongOrNull(value: Long?): JsonElement =
    value?.let { JsonPrimitive(it) } ?: JsonNull

private fun jsonIntOrNull(value: Int?): JsonElement =
    value?.let { JsonPrimitive(it) } ?: JsonNull

class PcVersionTool : McpTool {
    override val definition = McpToolDef(
        name = "version",
        description = "PC AnkiConnect compatible: report the compatible AnkiConnect API version.",
        inputSchema = emptySchema()
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult =
        McpToolCallResult(listOf(McpToolContent(text = JsonPrimitive(5).toString())))
}

class PcMultiTool(private val toolRegistry: ToolRegistry) : McpTool {
    override val definition = McpToolDef(
        name = "multi",
        description = "PC AnkiConnect compatible: run multiple supported actions in order.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "actions" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("array"),
                                "items" to JsonObject(mapOf("type" to JsonPrimitive("object")))
                            )
                        )
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("actions")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val actions = arguments?.get("actions")?.jsonArray
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: actions")
        if (actions.size > 100) {
            return pcBusinessError(BusinessErrorCodes.BATCH_TOO_LARGE, "multi 一次最多执行 100 个 action")
        }
        val results = actions.mapIndexed { index, element ->
            val action = element.jsonObject["action"]?.jsonPrimitive?.content
            if (action.isNullOrBlank()) {
                return@mapIndexed JsonObject(
                    mapOf(
                        "error" to JsonPrimitive("actions[$index].action is required"),
                        "code" to JsonPrimitive(BusinessErrorCodes.INVALID_ARGUMENT)
                    )
                )
            }
            if (action == definition.name) {
                return@mapIndexed JsonObject(
                    mapOf(
                        "error" to JsonPrimitive("nested multi is not supported"),
                        "code" to JsonPrimitive(BusinessErrorCodes.INVALID_ARGUMENT)
                    )
                )
            }
            val tool = toolRegistry.getTool(action)
                ?: return@mapIndexed JsonObject(
                    mapOf(
                        "error" to JsonPrimitive("unsupported action: $action"),
                        "code" to JsonPrimitive(BusinessErrorCodes.TOOL_NOT_FOUND)
                    )
                )
            val params = element.jsonObject["params"]?.jsonObject
            try {
                val result = tool.call(params)
                val text = result.content.firstOrNull()?.text ?: JsonNull.toString()
                val parsed = runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
                if (result.isError) {
                    JsonObject(
                        mapOf(
                            "error" to JsonPrimitive("action failed: $action"),
                            "result" to parsed
                        )
                    )
                } else {
                    parsed
                }
            } catch (e: ToolErrorException) {
                JsonObject(
                    mapOf(
                        "error" to JsonPrimitive(e.message),
                        "code" to JsonPrimitive(e.errorCode)
                    )
                )
            } catch (e: Exception) {
                JsonObject(
                    mapOf(
                        "error" to JsonPrimitive(e.message ?: "action failed: $action"),
                        "code" to JsonPrimitive(BusinessErrorCodes.INTERNAL_ERROR)
                    )
                )
            }
        }
        return McpToolCallResult(listOf(McpToolContent(text = JsonArray(results).toString())))
    }
}

private fun cardToJson(card: AnkiCardInfo, showAnswer: Boolean = true): JsonObject {
    val values = mutableMapOf<String, JsonElement>(
        "cardId" to JsonPrimitive(card.id),
        "noteId" to JsonPrimitive(card.noteId),
        "ord" to JsonPrimitive(card.ord),
        "deckId" to JsonPrimitive(card.deckId),
        "deckName" to JsonPrimitive(card.deckName),
        "cardName" to JsonPrimitive(card.cardName),
        "question" to JsonPrimitive(card.question),
        "questionSimple" to JsonPrimitive(card.questionSimple),
        "type" to jsonIntOrNull(card.type),
        "queue" to jsonIntOrNull(card.queue),
        "due" to (card.due?.let { JsonPrimitive(it) } ?: JsonNull),
        "interval" to jsonIntOrNull(card.interval),
        "easeFactor" to jsonIntOrNull(card.easeFactor),
        "reps" to jsonIntOrNull(card.reps),
        "lapses" to jsonIntOrNull(card.lapses)
    )
    if (showAnswer) {
        values["answer"] = JsonPrimitive(card.answer)
        values["answerSimple"] = JsonPrimitive(card.answerSimple)
        values["answerPure"] = JsonPrimitive(card.answerPure)
    }
    return JsonObject(values)
}

private fun cardCounts(cards: List<AnkiCardInfo>): JsonObject {
    val newCount = cards.count { it.queue == 0 }
    val learning = cards.count { it.queue == 1 || it.queue == 3 }
    val review = cards.count { it.queue == 2 }
    val other = cards.size - newCount - learning - review
    return JsonObject(
        mapOf(
            "total" to JsonPrimitive(cards.size),
            "new" to JsonPrimitive(newCount),
            "learning" to JsonPrimitive(learning),
            "review" to JsonPrimitive(review),
            "other" to JsonPrimitive(other.coerceAtLeast(0))
        )
    )
}

private fun fieldsOnTemplate(template: String?, knownFields: List<String>): JsonArray {
    if (template.isNullOrBlank()) return JsonArray(emptyList())
    val usedNames = LinkedHashSet<String>()
    val fieldByName = knownFields.associateBy { it }
    Regex("""\{\{\s*[#\^/]?\s*([^{}:]+?)\s*\}\}""").findAll(template).forEach { match ->
        val rawName = match.groupValues[1].trim()
        val fieldName = fieldByName[rawName] ?: knownFields.firstOrNull { it.equals(rawName, ignoreCase = true) }
        if (fieldName != null) usedNames.add(fieldName)
    }
    return stringArray(usedNames.toList())
}

private suspend fun duplicateNoteIdOrNull(
    ankiRepository: AnkiRepository,
    noteType: AnkiNoteTypeDetail,
    fields: Map<String, String>,
    options: JsonObject
): Long? {
    val nestedOptions = options["options"] as? JsonObject
    val allowDuplicate = options.booleanValue("allowDuplicate", "allow_duplicate")
        ?: nestedOptions?.booleanValue("allowDuplicate", "allow_duplicate")
        ?: false
    if (allowDuplicate) return null

    val duplicateScope = options.stringValue("duplicateScope", "duplicate_scope")
        ?: nestedOptions?.stringValue("duplicateScope", "duplicate_scope")
    if (duplicateScope != null && duplicateScope != "collection") {
        throw ToolErrorException(
            BusinessErrorCodes.INVALID_ARGUMENT,
            "安卓版 PC 兼容接口只支持 collection 级重复检测；duplicateScope=$duplicateScope 不支持"
        )
    }
    if (
        options["duplicateScopeOptions"] != null ||
        options["duplicate_scope_options"] != null ||
        nestedOptions?.get("duplicateScopeOptions") != null ||
        nestedOptions?.get("duplicate_scope_options") != null
    ) {
        throw ToolErrorException(
            BusinessErrorCodes.INVALID_ARGUMENT,
            "安卓版 PC 兼容接口不支持 duplicateScopeOptions"
        )
    }

    val mapped = mapNoteFields(noteType.fields, fields)
    val firstFieldValue = mapped.firstOrNull()?.trim().orEmpty()
    if (firstFieldValue.isEmpty()) return null
    return ankiRepository.findDuplicateNotes(noteType.id, firstFieldValue).firstOrNull()
}

private suspend fun canAddPcNote(
    ankiRepository: AnkiRepository,
    noteArgs: JsonObject,
    sharedDeckName: String? = null,
    sharedModelName: String? = null,
    sharedOptions: JsonObject = JsonObject(emptyMap())
): Boolean {
    val deckName = noteArgs.stringValue("deckName", "deck_name") ?: sharedDeckName
    if (deckName.isNullOrBlank()) return false
    val modelName = noteArgs.stringValue("modelName", "model_name") ?: sharedModelName
    if (modelName.isNullOrBlank()) return false
    if (noteArgs.unsupportedAttachmentNames().isNotEmpty()) return false
    val fields = noteArgs["fields"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: return false
    return try {
        val noteType = findNoteTypeByName(ankiRepository, modelName)
        val duplicateOptions = mutableMapOf<String, JsonElement>()
        duplicateOptions.putAll(sharedOptions)
        duplicateOptions.putAll(noteArgs)
        duplicateNoteIdOrNull(ankiRepository, noteType, fields, JsonObject(duplicateOptions)) == null
    } catch (e: ModelNotFoundException) {
        false
    } catch (e: FieldMappingException) {
        false
    } catch (e: ToolErrorException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }
}

class PcListDecksTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "listDecks",
        description = "PC Anki MCP compatible alias: list AnkiDroid deck names. includeStats is accepted but Android deck statistics are not available through the public ContentProvider API.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "includeStats" to JsonObject(mapOf("type" to JsonPrimitive("boolean")))
                    )
                ),
                "required" to JsonArray(emptyList())
            )
        )
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

class PcDeckNamesTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "deckNames",
        description = "AnkiConnect compatible alias: return AnkiDroid deck names as a JSON array.",
        inputSchema = emptySchema()
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult = try {
        val names = ankiRepository.listDecks().map { it.name }
        McpToolCallResult(listOf(McpToolContent(text = stringArray(names).toString())))
    } catch (e: Exception) {
        pcExceptionError(e)
    }
}

class PcDeckNamesAndIdsTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "deckNamesAndIds",
        description = "AnkiConnect compatible alias: return deck name to deck id mapping.",
        inputSchema = emptySchema()
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult = try {
        val decks = ankiRepository.listDecks()
        val json = JsonObject(decks.associate { it.name to JsonPrimitive(it.id) })
        McpToolCallResult(listOf(McpToolContent(text = json.toString())))
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
                    mapOf(
                        "deckName" to JsonObject(mapOf("type" to JsonPrimitive("string"), "minLength" to JsonPrimitive(1))),
                        "deck" to JsonObject(mapOf("type" to JsonPrimitive("string"), "minLength" to JsonPrimitive(1)))
                    )
                ),
                "required" to JsonArray(emptyList())
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val deckName = arguments?.stringValue("deckName", "deck")
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

class PcModelNamesAndIdsTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "modelNamesAndIds",
        description = "AnkiConnect compatible alias: return note type name to model id mapping.",
        inputSchema = emptySchema()
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult = try {
        val models = ankiRepository.listNoteTypes()
        val json = JsonObject(models.associate { it.name to JsonPrimitive(it.id) })
        McpToolCallResult(listOf(McpToolContent(text = json.toString())))
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
                "properties" to JsonObject(
                    mapOf(
                        "modelName" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "model_name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "model" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                    )
                ),
                "required" to JsonArray(emptyList())
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val modelName = arguments?.stringValue("modelName", "model_name", "model")
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
                        "deck_name" to JsonObject(mapOf("type" to JsonPrimitive("string"), "minLength" to JsonPrimitive(1))),
                        "modelName" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "model_name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "note" to JsonObject(mapOf("type" to JsonPrimitive("object"))),
                        "fields" to JsonObject(mapOf("type" to JsonPrimitive("object"))),
                        "tags" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
                        "allowDuplicate" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                        "duplicateScope" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "enum" to JsonArray(listOf(JsonPrimitive("collection")))
                            )
                        )
                    )
                ),
                "required" to JsonArray(emptyList())
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val noteArgs = arguments?.get("note")?.jsonObject ?: arguments
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数")
        val deckName = noteArgs.stringValue("deckName", "deck_name")
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: deckName")
        if (deckName.isBlank()) return pcBusinessError(BusinessErrorCodes.DECK_NAME_EMPTY, "deckName 不能为空")
        val modelName = noteArgs.stringValue("modelName", "model_name")
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: modelName")
        val attachments = noteArgs.unsupportedAttachmentNames()
        if (attachments.isNotEmpty()) {
            return pcBusinessError(
                BusinessErrorCodes.INVALID_ARGUMENT,
                "安卓版 addNote 不支持媒体附件字段: ${attachments.joinToString(", ")}"
            )
        }
        val fields = noteArgs["fields"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: fields")
        val tags = noteArgs.stringList("tags")
        return try {
            val noteType = findNoteTypeByName(ankiRepository, modelName)
            val duplicateId = duplicateNoteIdOrNull(ankiRepository, noteType, fields, noteArgs)
            if (duplicateId != null) {
                return McpToolCallResult(listOf(McpToolContent(text = JsonNull.toString())))
            }
            val result = ankiRepository.addNote(
                AddGenericNoteRequest(deck = deckName.trim(), noteTypeId = noteType.id, fields = fields, tags = tags)
            )
            McpToolCallResult(listOf(McpToolContent(text = jsonLongOrNull(result.noteId).toString())), isError = !result.success)
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcAddNotesTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "addNotes",
        description = "PC Anki MCP compatible alias: add up to 100 notes. Supports AnkiConnect-style per-note deckName/modelName and the Android bridge's shared top-level deckName/modelName.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "deckName" to JsonObject(mapOf("type" to JsonPrimitive("string"), "minLength" to JsonPrimitive(1))),
                        "deck_name" to JsonObject(mapOf("type" to JsonPrimitive("string"), "minLength" to JsonPrimitive(1))),
                        "modelName" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "model_name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "notes" to JsonObject(mapOf("type" to JsonPrimitive("array"))),
                        "tags" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
                        "allowDuplicate" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                        "duplicateScope" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "enum" to JsonArray(listOf(JsonPrimitive("collection")))
                            )
                        )
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("notes")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val args = arguments ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数")
        val sharedDeckName = args.stringValue("deckName", "deck_name")
        val sharedModelName = args.stringValue("modelName", "model_name")
        val notesArray = args["notes"]?.jsonArray
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: notes")
        if (notesArray.isEmpty()) return pcBusinessError(BusinessErrorCodes.INVALID_ARGUMENT, "notes 不能为空")
        if (notesArray.size > 100) return pcBusinessError(BusinessErrorCodes.BATCH_TOO_LARGE, "一次最多添加 100 张笔记")
        val sharedTags = args.stringList("tags")
        return try {
            val noteIds = notesArray.mapIndexed { index, element ->
                val obj = element.jsonObject
                val attachments = obj.unsupportedAttachmentNames()
                if (attachments.isNotEmpty()) {
                    throw ToolErrorException(
                        BusinessErrorCodes.INVALID_ARGUMENT,
                        "安卓版 addNotes 不支持 notes[$index] 的媒体附件字段: ${attachments.joinToString(", ")}"
                    )
                }
                val deckName = obj.stringValue("deckName", "deck_name") ?: sharedDeckName
                    ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: notes[$index].deckName")
                if (deckName.isBlank()) {
                    throw ToolErrorException(BusinessErrorCodes.DECK_NAME_EMPTY, "notes[$index].deckName 不能为空")
                }
                val modelName = obj.stringValue("modelName", "model_name") ?: sharedModelName
                    ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: notes[$index].modelName")
                val noteType = findNoteTypeByName(ankiRepository, modelName)
                val fields = obj["fields"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }
                    ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "notes[$index] 缺少参数: fields")
                val duplicateOptions = mutableMapOf<String, JsonElement>()
                duplicateOptions.putAll(args)
                duplicateOptions.putAll(obj)
                val duplicateId = duplicateNoteIdOrNull(ankiRepository, noteType, fields, JsonObject(duplicateOptions))
                if (duplicateId != null) {
                    JsonNull
                } else {
                    val noteTags = obj.stringList("tags")
                    val result = ankiRepository.addNote(
                        AddGenericNoteRequest(
                            deck = deckName.trim(),
                            noteTypeId = noteType.id,
                            fields = fields,
                            tags = (sharedTags + noteTags).distinct()
                        )
                    )
                    jsonLongOrNull(result.noteId)
                }
            }
            McpToolCallResult(listOf(McpToolContent(text = JsonArray(noteIds).toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcCanAddNotesTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "canAddNotes",
        description = "AnkiConnect compatible alias: validate candidate notes without writing anything.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "deckName" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "deck_name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "modelName" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "model_name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "notes" to JsonObject(mapOf("type" to JsonPrimitive("array"))),
                        "allowDuplicate" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                        "duplicateScope" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("notes")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val args = arguments ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数")
        val notesArray = args["notes"]?.jsonArray
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: notes")
        val sharedDeckName = args.stringValue("deckName", "deck_name")
        val sharedModelName = args.stringValue("modelName", "model_name")
        return try {
            val results = notesArray.map { element ->
                JsonPrimitive(
                    canAddPcNote(
                        ankiRepository = ankiRepository,
                        noteArgs = element.jsonObject,
                        sharedDeckName = sharedDeckName,
                        sharedModelName = sharedModelName,
                        sharedOptions = args
                    )
                )
            }
            McpToolCallResult(listOf(McpToolContent(text = JsonArray(results).toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
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

class PcFindCardsTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "findCards",
        description = "AnkiConnect compatible alias: search cards by Anki browser query where AnkiDroid can search notes.",
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
            val noteIds = ankiRepository.findNotes(query).toSet()
            val cards = ankiRepository.getCards(limit = Int.MAX_VALUE)
                .filter { it.noteId in noteIds }
                .map { JsonPrimitive(it.id) }
            McpToolCallResult(listOf(McpToolContent(text = JsonArray(cards).toString())))
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
            val requestedIds = ids.toSet()
            val cardsByNoteId = ankiRepository.getCards(limit = Int.MAX_VALUE)
                .filter { it.noteId in requestedIds }
                .groupBy { it.noteId }
            val json = JsonArray(infos.map { info ->
                JsonObject(
                    mapOf(
                        "noteId" to JsonPrimitive(info.id),
                        "modelName" to JsonPrimitive(info.modelName),
                        "modelId" to JsonPrimitive(info.noteTypeId),
                        "noteTypeId" to JsonPrimitive(info.noteTypeId),
                        "fields" to JsonObject(
                            info.fields.entries.mapIndexed { index, entry ->
                                entry.key to JsonObject(
                                    mapOf(
                                        "value" to JsonPrimitive(entry.value),
                                        "order" to JsonPrimitive(index)
                                    )
                                )
                            }.toMap()
                        ),
                        "tags" to stringArray(info.tags),
                        "cards" to JsonArray(
                            cardsByNoteId[info.id]
                                .orEmpty()
                                .sortedBy { it.ord }
                                .map { JsonPrimitive(it.id) }
                        ),
                        "css" to (info.css?.let { JsonPrimitive(it) } ?: JsonNull)
                    )
                )
            })
            McpToolCallResult(listOf(McpToolContent(text = json.toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcCardsInfoTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "cardsInfo",
        description = "AnkiConnect compatible alias: return card info for card ids that AnkiDroid can read.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf("cards" to JsonObject(mapOf("type" to JsonPrimitive("array"))))),
                "required" to JsonArray(listOf(JsonPrimitive("cards")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val cardIds = arguments?.get("cards")?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: cards")
        return try {
            val cards = cardIds.mapNotNull { ankiRepository.presentCard(it) }
            McpToolCallResult(listOf(McpToolContent(text = JsonArray(cards.map { cardToJson(it) }).toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcCardsToNotesTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "cardsToNotes",
        description = "AnkiConnect compatible alias: map card ids to their note ids.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf("cards" to JsonObject(mapOf("type" to JsonPrimitive("array"))))),
                "required" to JsonArray(listOf(JsonPrimitive("cards")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val cardIds = arguments?.get("cards")?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: cards")
        return try {
            val noteIds = cardIds.mapNotNull { ankiRepository.presentCard(it)?.noteId }.distinct()
            McpToolCallResult(listOf(McpToolContent(text = JsonArray(noteIds.map { JsonPrimitive(it) }).toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcGetDecksTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "getDecks",
        description = "AnkiConnect compatible alias: group the provided card ids by deck name.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf("cards" to JsonObject(mapOf("type" to JsonPrimitive("array"))))),
                "required" to JsonArray(listOf(JsonPrimitive("cards")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val cardIds = arguments?.get("cards")?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: cards")
        return try {
            val byDeck = mutableMapOf<String, MutableList<JsonElement>>()
            cardIds.forEach { cardId ->
                val card = ankiRepository.presentCard(cardId) ?: return@forEach
                byDeck.getOrPut(card.deckName) { mutableListOf() }.add(JsonPrimitive(card.id))
            }
            val json = JsonObject(byDeck.mapValues { JsonArray(it.value) })
            McpToolCallResult(listOf(McpToolContent(text = json.toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcSuspendTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "suspend",
        description = "AnkiConnect compatible alias: suspend cards by card id. Android cannot unsuspend through the public API.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf("cards" to JsonObject(mapOf("type" to JsonPrimitive("array"))))),
                "required" to JsonArray(listOf(JsonPrimitive("cards")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val cardIds = arguments?.get("cards")?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: cards")
        return try {
            val updated = ankiRepository.suspendCards(cardIds)
            McpToolCallResult(listOf(McpToolContent(text = JsonPrimitive(updated > 0).toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcAreSuspendedTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "areSuspended",
        description = "AnkiConnect compatible alias: return whether each provided card is suspended.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf("cards" to JsonObject(mapOf("type" to JsonPrimitive("array"))))),
                "required" to JsonArray(listOf(JsonPrimitive("cards")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val cardIds = arguments?.get("cards")?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: cards")
        return try {
            val json = JsonArray(ankiRepository.areSuspended(cardIds).map { JsonPrimitive(it) })
            McpToolCallResult(listOf(McpToolContent(text = json.toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcAreDueTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "areDue",
        description = "AnkiConnect compatible alias: return whether each provided card is due according to AnkiDroid's scheduler list.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf("cards" to JsonObject(mapOf("type" to JsonPrimitive("array"))))),
                "required" to JsonArray(listOf(JsonPrimitive("cards")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val cardIds = arguments?.get("cards")?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: cards")
        return try {
            val json = JsonArray(ankiRepository.areDue(cardIds).map { JsonPrimitive(it) })
            McpToolCallResult(listOf(McpToolContent(text = json.toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcGetIntervalsTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "getIntervals",
        description = "AnkiConnect compatible alias: return the most recent interval for each card. complete=true is not available on Android.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "cards" to JsonObject(mapOf("type" to JsonPrimitive("array"))),
                        "complete" to JsonObject(mapOf("type" to JsonPrimitive("boolean")))
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("cards")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val cardIds = arguments?.get("cards")?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: cards")
        if (arguments.booleanValue("complete") == true) {
            return pcBusinessError(BusinessErrorCodes.INVALID_ARGUMENT, "安卓版 getIntervals 不支持 complete=true 的历史间隔")
        }
        return try {
            val json = JsonArray(ankiRepository.getIntervals(cardIds).map { JsonPrimitive(it) })
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
        val attachments = note.unsupportedAttachmentNames()
        if (attachments.isNotEmpty()) {
            return pcBusinessError(
                BusinessErrorCodes.INVALID_ARGUMENT,
                "安卓版 updateNoteFields 只支持 fields 字段更新，不支持媒体附件更新: ${attachments.joinToString(", ")}"
            )
        }
        val fields = note["fields"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: note.fields")
        return try {
            val updated = ankiRepository.updateNoteFields(id, fields)
            McpToolCallResult(listOf(McpToolContent(text = JsonNull.toString())), isError = !updated)
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcGetTagsTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "getTags",
        description = "PC Anki MCP compatible: list tags, optionally filtered by substring.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf("pattern" to JsonObject(mapOf("type" to JsonPrimitive("string"))))
                ),
                "required" to JsonArray(emptyList())
            )
        )
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
            if (add) ankiRepository.addTags(ids, tags) else ankiRepository.removeTags(ids, tags)
            McpToolCallResult(listOf(McpToolContent(text = JsonNull.toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcAddTagsTool(ankiRepository: AnkiRepository) : PcTagMutationTool(ankiRepository, "addTags", add = true)

class PcRemoveTagsTool(ankiRepository: AnkiRepository) : PcTagMutationTool(ankiRepository, "removeTags", add = false)

class PcReplaceTagsTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "replaceTags",
        description = "PC Anki MCP compatible: rename one tag across selected notes.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "notes" to JsonObject(mapOf("type" to JsonPrimitive("array"))),
                        "tagToReplace" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "tag_to_replace" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "replaceWithTag" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "replace_with_tag" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("notes")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val ids = arguments?.get("notes")?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: notes")
        val from = arguments.stringValue("tagToReplace", "tag_to_replace")
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: tagToReplace")
        val to = arguments.stringValue("replaceWithTag", "replace_with_tag")
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: replaceWithTag")
        return try {
            val updated = ankiRepository.replaceTags(ids, from, to)
            McpToolCallResult(listOf(McpToolContent(text = JsonObject(mapOf("updated" to JsonPrimitive(updated))).toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcModelTemplatesTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "modelTemplates",
        description = "PC Anki MCP compatible: read card templates for a note type by modelName.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "modelName" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "model_name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "model" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                    )
                ),
                "required" to JsonArray(emptyList())
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val modelName = arguments?.stringValue("modelName", "model_name", "model")
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: modelName")
        return try {
            val detail = findNoteTypeByName(ankiRepository, modelName)
            val templates = detail.templates.associate { template ->
                template.name to JsonObject(
                    mapOf(
                        "Front" to JsonPrimitive(template.frontTemplate.orEmpty()),
                        "Back" to JsonPrimitive(template.backTemplate.orEmpty())
                    )
                )
            }
            McpToolCallResult(listOf(McpToolContent(text = JsonObject(templates).toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcModelFieldsOnTemplatesTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "modelFieldsOnTemplates",
        description = "PC Anki MCP compatible: read which fields appear on each card template side.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf("modelName" to JsonObject(mapOf("type" to JsonPrimitive("string"))))
                ),
                "required" to JsonArray(listOf(JsonPrimitive("modelName")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val modelName = arguments?.stringValue("modelName", "model_name", "model")
            ?: throw ToolErrorException(BusinessErrorCodes.INVALID_ARGUMENT, "modelName is required")
        return try {
            val detail = findNoteTypeByName(ankiRepository, modelName)
            val result = detail.templates.associate { template ->
                template.name to JsonArray(
                    listOf(
                        fieldsOnTemplate(template.frontTemplate, detail.fields),
                        fieldsOnTemplate(template.backTemplate, detail.fields)
                    )
                )
            }
            McpToolCallResult(listOf(McpToolContent(text = JsonObject(result).toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcModelStylingTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "modelStyling",
        description = "PC Anki MCP compatible: read CSS styling for a note type by modelName.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "modelName" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "model_name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "model" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                    )
                ),
                "required" to JsonArray(emptyList())
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val modelName = arguments?.stringValue("modelName", "model_name", "model")
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: modelName")
        return try {
            val detail = findNoteTypeByName(ankiRepository, modelName)
            McpToolCallResult(listOf(McpToolContent(text = JsonObject(mapOf("css" to JsonPrimitive(detail.css.orEmpty()))).toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcGetCardsTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "get_cards",
        description = "PC Anki MCP compatible: retrieve cards with optional deck and state filters.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "deck_name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "deckName" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "card_state" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "enum" to JsonArray(listOf("due", "new", "learning", "suspended", "buried").map { JsonPrimitive(it) })
                            )
                        ),
                        "cardState" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "enum" to JsonArray(listOf("due", "new", "learning", "suspended", "buried").map { JsonPrimitive(it) })
                            )
                        ),
                        "limit" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "minimum" to JsonPrimitive(1), "maximum" to JsonPrimitive(500)))
                    )
                ),
                "required" to JsonArray(emptyList())
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult = try {
        val deckName = arguments?.stringValue("deck_name", "deckName")
        val state = arguments?.stringValue("card_state", "cardState")
        val limit = (arguments?.get("limit")?.jsonPrimitive?.content?.toIntOrNull() ?: 100).coerceIn(1, 500)
        val cards = ankiRepository.getCards(deckName, state, limit)
        McpToolCallResult(listOf(McpToolContent(text = JsonArray(cards.map { cardToJson(it) }).toString())))
    } catch (e: Exception) {
        pcExceptionError(e)
    }
}

class PcGetDueCardsTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "get_due_cards",
        description = "PC Anki MCP compatible: retrieve scheduled cards due for review from AnkiDroid.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "deck_name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "deckName" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "include_learning" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                        "include_new" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                        "limit" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "minimum" to JsonPrimitive(1), "maximum" to JsonPrimitive(100)))
                    )
                ),
                "required" to JsonArray(emptyList())
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult = try {
        val deckName = arguments?.stringValue("deck_name", "deckName")
        val limit = (arguments?.get("limit")?.jsonPrimitive?.content?.toIntOrNull() ?: 20).coerceIn(1, 100)
        val cards = ankiRepository.getDueCards(deckName, limit)
        McpToolCallResult(listOf(McpToolContent(text = JsonArray(cards.map { cardToJson(it) }).toString())))
    } catch (e: Exception) {
        pcExceptionError(e)
    }
}

class PcPresentCardTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "present_card",
        description = "PC Anki MCP compatible: retrieve one card's question and optionally answer.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "card_id" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                        "cardId" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                        "show_answer" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                        "showAnswer" to JsonObject(mapOf("type" to JsonPrimitive("boolean")))
                    )
                ),
                "required" to JsonArray(emptyList())
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val cardId = arguments?.longValue("card_id", "cardId")
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少或非法参数: card_id")
        return try {
            val showAnswer = arguments.booleanValue("show_answer", "showAnswer") ?: false
            val card = ankiRepository.presentCard(cardId)
            McpToolCallResult(listOf(McpToolContent(text = (card?.let { cardToJson(it, showAnswer) } ?: JsonNull).toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcChangeDeckTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "changeDeck",
        description = "PC Anki MCP compatible: move cards to another deck, creating the deck if needed.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "cards" to JsonObject(mapOf("type" to JsonPrimitive("array"))),
                        "deck" to JsonObject(mapOf("type" to JsonPrimitive("string"), "minLength" to JsonPrimitive(1)))
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("cards"), JsonPrimitive("deck")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val cards = arguments?.get("cards")?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: cards")
        val deck = arguments["deck"]?.jsonPrimitive?.content
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: deck")
        if (deck.isBlank()) return pcBusinessError(BusinessErrorCodes.DECK_NAME_EMPTY, "deck 不能为空")
        return try {
            ankiRepository.changeDeck(cards, deck)
            McpToolCallResult(listOf(McpToolContent(text = JsonNull.toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcRateCardTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "rate_card",
        description = "PC Anki MCP compatible: submit a review rating for a card. Only call after user confirmation.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "card_id" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                        "cardId" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                        "rating" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "minimum" to JsonPrimitive(1), "maximum" to JsonPrimitive(4))),
                        "time_taken_ms" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "minimum" to JsonPrimitive(0))),
                        "timeTakenMs" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "minimum" to JsonPrimitive(0)))
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("rating")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val cardId = arguments?.longValue("card_id", "cardId")
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少或非法参数: card_id")
        val rating = arguments.intValue("rating")
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少或非法参数: rating")
        val timeTaken = arguments.longValue("time_taken_ms", "timeTakenMs") ?: 0L
        return try {
            val ok = ankiRepository.rateCard(cardId, rating, timeTaken)
            McpToolCallResult(listOf(McpToolContent(text = JsonObject(mapOf("rated" to JsonPrimitive(ok))).toString())), isError = !ok)
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcDeckStatsTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "deckStats",
        description = "PC Anki MCP compatible best-effort: card counts for one deck from AnkiDroid Card API.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf("deck" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
                "required" to JsonArray(listOf(JsonPrimitive("deck")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val deck = arguments?.get("deck")?.jsonPrimitive?.content
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: deck")
        return try {
            val cards = ankiRepository.getCards(deckName = deck, limit = Int.MAX_VALUE)
            val json = JsonObject(
                mapOf(
                    "deck" to JsonPrimitive(deck),
                    "counts" to cardCounts(cards),
                    "statsLevel" to JsonPrimitive("android_card_counts")
                )
            )
            McpToolCallResult(listOf(McpToolContent(text = json.toString())))
        } catch (e: Exception) {
            pcExceptionError(e)
        }
    }
}

class PcCollectionStatsTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "collection_stats",
        description = "PC Anki MCP compatible best-effort: aggregate card counts across decks.",
        inputSchema = emptySchema()
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult = try {
        val cards = ankiRepository.getCards(limit = Int.MAX_VALUE)
        val byDeck = cards.groupBy { it.deckName }
            .mapValues { (_, deckCards) -> cardCounts(deckCards) }
            .mapKeys { it.key.ifBlank { "(unknown)" } }
        val json = JsonObject(
            mapOf(
                "counts" to cardCounts(cards),
                "decks" to JsonObject(byDeck),
                "statsLevel" to JsonPrimitive("android_card_counts"),
                "limitApplied" to JsonPrimitive(false)
            )
        )
        McpToolCallResult(listOf(McpToolContent(text = json.toString())))
    } catch (e: Exception) {
        pcExceptionError(e)
    }
}
