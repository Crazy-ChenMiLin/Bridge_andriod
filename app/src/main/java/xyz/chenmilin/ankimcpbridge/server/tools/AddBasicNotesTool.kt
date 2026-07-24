package xyz.chenmilin.ankimcpbridge.server.tools

import kotlinx.serialization.json.*
import xyz.chenmilin.ankimcpbridge.anki.*
import xyz.chenmilin.ankimcpbridge.server.*

class AddBasicNotesTool(private val ankiRepository: AnkiRepository) : McpTool {

    override val definition = McpToolDef(
        name = "add_basic_notes",
        description = "批量向指定牌组添加 Basic 类型（正面/背面）卡片，一次最多 100 张。当一段对话里提炼出多个值得记忆的知识点时，把整批卡片一次性写入，比反复调用 add_basic_note 更高效。",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "deck" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("目标牌组名称")
                            )
                        ),
                        "notes" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("array"),
                                "description" to JsonPrimitive("卡片列表，每张包含 front、back 和可选的 tags"),
                                "items" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("object"),
                                        "properties" to JsonObject(
                                            mapOf(
                                                "front" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                "back" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                "tags" to JsonObject(
                                                    mapOf(
                                                        "type" to JsonPrimitive("array"),
                                                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                                                    )
                                                )
                                            )
                                        ),
                                        "required" to JsonArray(
                                            listOf(JsonPrimitive("front"), JsonPrimitive("back"))
                                        )
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
            return businessError(BusinessErrorCodes.BATCH_TOO_LARGE, "一次最多添加 100 张卡片，当前: ${notesArray.size}")
        }

        // 解析全部卡片（保留原始顺序，错误索引由 AnkiRepository 按原始下标返回）
        val notes = notesArray.map { noteElement ->
            val noteObj = noteElement.jsonObject
            val front = noteObj["front"]?.jsonPrimitive?.content ?: ""
            val back = noteObj["back"]?.jsonPrimitive?.content ?: ""
            val tags = noteObj["tags"]?.jsonArray?.mapNotNull {
                val s = it.jsonPrimitive.content.trim()
                if (s.isNotBlank()) s else null
            }?.distinct() ?: emptyList()
            SingleNoteRequest(front = front, back = back, tags = tags)
        }

        return try {
            val request = AddBasicNotesRequest(deck = deck.trim(), notes = notes)
            val result = ankiRepository.addBasicNotes(request)

            val allErrors = result.errors
            val json = JsonObject(
                mapOf(
                    "requested" to JsonPrimitive(notesArray.size),
                    "submitted" to JsonPrimitive(result.submitted),
                    "succeeded" to JsonPrimitive(result.succeeded),
                    "failed" to JsonPrimitive(result.failed),
                    "noteIds" to JsonArray(result.noteIds.map { JsonPrimitive(it) }),
                    "noteIdsAvailable" to JsonPrimitive(result.noteIdsAvailable),
                    "errors" to JsonArray(allErrors.map { err ->
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
            // 存在失败（预校验未通过 / 批量部分或全部失败）时标记 isError=true
            McpToolCallResult(
                content = listOf(McpToolContent(text = json.toString())),
                isError = result.failed > 0
            )
        } catch (e: AnkiDroidNotInstalledException) {
            businessError(BusinessErrorCodes.ANKIDROID_NOT_INSTALLED, e.message ?: "AnkiDroid 未安装")
        } catch (e: AnkiPermissionDeniedException) {
            businessError(BusinessErrorCodes.ANKI_PERMISSION_DENIED, e.message ?: "AnkiDroid 权限未授权")
        } catch (e: ModelNotFoundException) {
            businessError(BusinessErrorCodes.MODEL_NOT_FOUND, e.message ?: "笔记类型未找到")
        } catch (e: Exception) {
            businessError(BusinessErrorCodes.INTERNAL_ERROR, e.message ?: "内部错误")
        }
    }
}
