package xyz.chenmilin.ankimcpbridge.server.tools

import kotlinx.serialization.json.*
import xyz.chenmilin.ankimcpbridge.anki.*
import xyz.chenmilin.ankimcpbridge.server.*

class AddNotesTool(private val ankiRepository: AnkiRepository) : McpTool {

    override val definition = McpToolDef(
        name = "add_notes",
        description = "批量按指定笔记类型写入多张笔记，一次最多 100 张。每条可指定自己的 noteTypeId、fields 与 tags（fields 的键名需与目标笔记类型字段名一致，未知字段会报错）。同一批次共享同一个 deck。写入后会回读验证持久化并通知 AnkiDroid 本地刷新（非 AnkiWeb 云同步）。",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "deck" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("目标牌组名称"))),
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
            McpToolCallResult(
                content = listOf(McpToolContent(text = json.toString())),
                isError = result.failed > 0
            )
        } catch (e: AnkiDroidNotInstalledException) {
            businessError(BusinessErrorCodes.ANKIDROID_NOT_INSTALLED, e.message ?: "AnkiDroid 未安装")
        } catch (e: AnkiPermissionDeniedException) {
            businessError(BusinessErrorCodes.ANKI_PERMISSION_DENIED, e.message ?: "AnkiDroid 权限未授权")
        } catch (e: FieldMappingException) {
            businessError(e.code, e.message ?: "字段映射失败")
        } catch (e: ModelNotFoundException) {
            businessError(BusinessErrorCodes.MODEL_NOT_FOUND, e.message ?: "笔记类型未找到")
        } catch (e: Exception) {
            businessError(BusinessErrorCodes.INTERNAL_ERROR, e.message ?: "内部错误")
        }
    }
}
