package xyz.chenmilin.ankimcpbridge.server.tools

import kotlinx.serialization.json.*
import xyz.chenmilin.ankimcpbridge.anki.*
import xyz.chenmilin.ankimcpbridge.server.*

class AddNoteTool(private val ankiRepository: AnkiRepository) : McpTool {

    override val definition = McpToolDef(
        name = "add_note",
        description = "按指定笔记类型（noteTypeId）向目标牌组写入一张笔记，支持任意字段（不限于 Basic 的 Front/Back）。字段名必须与 list_note_types / get_note_type 返回的字段名一致；未提供的字段写入空字符串，输入中出现未知字段会报错。Cloze 类型不会强制 Front/Back 规则。写入后会回读验证数据已持久化，并通知 AnkiDroid 刷新（本地刷新，非 AnkiWeb 云同步）。目标牌组不存在时会自动创建，无需先调用 ensure_deck。注意：每个工具调用都是无状态的，ensure_deck 并不会为后续调用“记住”当前牌组，每次写入都必须在参数里显式给出 deck。推荐流程：list_note_types → get_note_type → add_note/add_notes。",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "deck" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "minLength" to JsonPrimitive(1),
                                "description" to JsonPrimitive("目标牌组名称（必填，不可为空；不存在时自动创建）")
                            )
                        ),
                        "noteTypeId" to JsonObject(
                            mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("笔记类型 ID（来自 list_note_types）"))
                        ),
                        "fields" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("object"),
                                "description" to JsonPrimitive("字段名 -> 内容 的映射，键名需与笔记类型字段名一致"),
                                "additionalProperties" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                            )
                        ),
                        "tags" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("array"),
                                "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "description" to JsonPrimitive("标签列表（可选）")
                            )
                        )
                    )
                ),
                "required" to JsonArray(
                    listOf(JsonPrimitive("deck"), JsonPrimitive("noteTypeId"), JsonPrimitive("fields"))
                )
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val deck = arguments?.get("deck")?.jsonPrimitive?.content
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: deck")
        if (deck.isBlank()) {
            return businessError(BusinessErrorCodes.DECK_NAME_EMPTY, "deck 不能为空，必须显式提供目标牌组名称（不存在将自动创建）")
        }
        val noteTypeId = arguments["noteTypeId"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少或非法参数: noteTypeId")
        val fieldsObj = arguments["fields"]?.jsonObject
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: fields")
        if (fieldsObj.isEmpty()) {
            return businessError(BusinessErrorCodes.NO_VALID_FIELD, "fields 不能为空")
        }

        val fields = fieldsObj.mapValues { it.value.jsonPrimitive.content }
        val tags = arguments["tags"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content.trim().takeIf { s -> s.isNotBlank() } }
            ?.distinct() ?: emptyList()

        return try {
            val request = AddGenericNoteRequest(
                deck = deck.trim(),
                noteTypeId = noteTypeId,
                fields = fields,
                tags = tags
            )
            val result = ankiRepository.addNote(request)
            val json = JsonObject(
                mapOf(
                    "success" to JsonPrimitive(result.success),
                    "noteId" to (result.noteId?.let { JsonPrimitive(it) } ?: JsonNull),
                    "deck" to JsonPrimitive(result.deck),
                    "noteTypeId" to JsonPrimitive(result.noteTypeId),
                    "persisted" to JsonPrimitive(result.persisted),
                    "refreshNotified" to JsonPrimitive(result.refreshNotified),
                    "deckId" to JsonPrimitive(result.deckId),
                    "deckCreated" to JsonPrimitive(result.deckCreated)
                )
            )
            // 写入成功但回读验证未通过时，标记为业务错误（isError=true）以提示客户端。
            McpToolCallResult(
                content = listOf(McpToolContent(text = json.toString())),
                isError = !result.persisted
            )
        } catch (e: AnkiDroidNotInstalledException) {
            businessError(BusinessErrorCodes.ANKIDROID_NOT_INSTALLED, e.message ?: "AnkiDroid 未安装")
        } catch (e: AnkiPermissionDeniedException) {
            businessError(BusinessErrorCodes.ANKI_PERMISSION_DENIED, e.message ?: "AnkiDroid 权限未授权")
        } catch (e: ModelNotFoundException) {
            businessError(BusinessErrorCodes.NOTE_TYPE_NOT_FOUND, e.message ?: "笔记类型未找到")
        } catch (e: FieldMappingException) {
            businessError(e.code, e.message ?: "字段映射失败")
        } catch (e: AddNoteException) {
            businessError(BusinessErrorCodes.ADD_NOTE_FAILED, e.message ?: "添加笔记失败")
        } catch (e: IllegalArgumentException) {
            businessError(BusinessErrorCodes.INVALID_NOTE_TYPE_ID, e.message ?: "参数非法")
        } catch (e: Exception) {
            businessError(BusinessErrorCodes.INTERNAL_ERROR, e.message ?: "内部错误")
        }
    }
}
