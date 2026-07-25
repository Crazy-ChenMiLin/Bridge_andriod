package xyz.chenmilin.ankimcpbridge.server.tools

import kotlinx.serialization.json.*
import xyz.chenmilin.ankimcpbridge.anki.*
import xyz.chenmilin.ankimcpbridge.server.*

class AddNotesTool(private val ankiRepository: AnkiRepository) : McpTool {

    override val definition = McpToolDef(
        name = "add_notes",
        description = "批量按指定笔记类型写入多张笔记，一次最多 100 张。每条可指定自己的 noteTypeId、fields 与 tags（fields 的键名需与目标笔记类型字段名一致，未知字段会报错）。同一批次共享同一个 deck。写入后会回读验证持久化并通知 AnkiDroid 本地刷新（非 AnkiWeb 云同步）。目标牌组不存在时会自动创建，无需先调用 ensure_deck。注意：每个工具调用都是无状态的，ensure_deck 并不会为后续调用“记住”当前牌组，每次写入都必须在参数里显式给出 deck。推荐流程：list_note_types → get_note_type → add_note/add_notes。",
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
                        "notes" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("array"),
                                "description" to JsonPrimitive("笔记列表，每项含 noteTypeId、fields、可选 tags"),
                                "items" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("object"),
                                        "properties" to JsonObject(
                                            mapOf(
                                                "noteTypeId" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("笔记类型 ID"))),
                                                "fields" to JsonObject(mapOf("type" to JsonPrimitive("object"), "additionalProperties" to JsonObject(mapOf("type" to JsonPrimitive("string"))), "description" to JsonPrimitive("字段名->内容的映射"))),
                                                "tags" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))), "description" to JsonPrimitive("标签（可选）")))
                                            )
                                        ),
                                        "required" to JsonArray(listOf(JsonPrimitive("noteTypeId"), JsonPrimitive("fields")))
                                    )
                                )
                            )
                        )
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("deck"), JsonPrimitive("notes")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val deck = arguments?.get("deck")?.jsonPrimitive?.content
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: deck")
        if (deck.isBlank()) {
            return businessError(BusinessErrorCodes.DECK_NAME_EMPTY, "deck 不能为空，必须显式提供目标牌组名称（不存在将自动创建）")
        }
        val notesArray = arguments["notes"]?.jsonArray
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: notes")

        if (notesArray.isEmpty()) {
            return businessError(BusinessErrorCodes.INVALID_ARGUMENT, "notes 不能为空")
        }
        if (notesArray.size > 100) {
            return businessError(BusinessErrorCodes.BATCH_TOO_LARGE, "一次最多添加 100 张笔记，当前: ${notesArray.size}")
        }

        val notes = notesArray.map { el ->
            val obj = el.jsonObject
            val noteTypeId = obj["noteTypeId"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少或非法参数: noteTypeId")
            val fieldsObj = obj["fields"]?.jsonObject
                ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: fields")
            val fields = fieldsObj.mapValues { it.value.jsonPrimitive.content }
            val tags = obj["tags"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content.trim().takeIf { s -> s.isNotBlank() } }
                ?.distinct() ?: emptyList()
            GenericNoteItem(noteTypeId = noteTypeId, fields = fields, tags = tags)
        }

        return try {
            val request = AddGenericNotesRequest(deck = deck.trim(), notes = notes)
            val result = ankiRepository.addNotes(request)
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
                    "deckId" to JsonPrimitive(result.deckId),
                    "deckCreated" to JsonPrimitive(result.deckCreated),
                    "errors" to JsonArray(result.errors.map { err ->
                        JsonObject(
                            mapOf(
                                "index" to JsonPrimitive(err.index),
                                "code" to JsonPrimitive(err.code),
                                "message" to JsonPrimitive(err.message)
                            )
                        )
                    })
                )
            )
            // isError 统一判定：失败数 >0 / 存在错误（含预校验）/ 写入成功但持久化失败。
            // 仅 refreshNotified=false 不单独判错（刷新是 best-effort，不影响数据写入成功）。
            McpToolCallResult(
                content = listOf(McpToolContent(text = json.toString())),
                isError = shouldMarkBatchToolError(result)
            )
        } catch (e: AnkiDroidNotInstalledException) {
            businessError(BusinessErrorCodes.ANKIDROID_NOT_INSTALLED, e.message ?: "AnkiDroid 未安装")
        } catch (e: AnkiPermissionDeniedException) {
            businessError(BusinessErrorCodes.ANKI_PERMISSION_DENIED, e.message ?: "AnkiDroid 权限未授权")
        } catch (e: FieldMappingException) {
            businessError(e.code, e.message ?: "字段映射失败")
        } catch (e: ModelNotFoundException) {
            businessError(BusinessErrorCodes.NOTE_TYPE_NOT_FOUND, e.message ?: "笔记类型未找到")
        } catch (e: Exception) {
            businessError(BusinessErrorCodes.INTERNAL_ERROR, e.message ?: "内部错误")
        }
    }
}
