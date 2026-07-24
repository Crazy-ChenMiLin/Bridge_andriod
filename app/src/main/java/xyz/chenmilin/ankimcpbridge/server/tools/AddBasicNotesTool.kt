package xyz.chenmilin.ankimcpbridge.server.tools

import kotlinx.serialization.json.*
import xyz.chenmilin.ankimcpbridge.anki.*
import xyz.chenmilin.ankimcpbridge.server.*

class AddBasicNotesTool(private val ankiRepository: AnkiRepository) : McpTool {

    override val definition = McpToolDef(
        name = "add_basic_notes",
        description = "批量向指定牌组添加 Basic 类型卡片。一次最多 100 张。",
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
            throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "notes 不能为空")
        }
        if (notesArray.size > 100) {
            throwToolError(BusinessErrorCodes.BATCH_TOO_LARGE, "一次最多添加 100 张卡片，当前: ${notesArray.size}")
        }

        // 预校验所有卡片
        val validationErrors = mutableListOf<BatchError>()
        val validNotes = mutableListOf<SingleNoteRequest>()

        for ((index, noteElement) in notesArray.withIndex()) {
            val noteObj = noteElement.jsonObject
            val front = noteObj["front"]?.jsonPrimitive?.content ?: ""
            val back = noteObj["back"]?.jsonPrimitive?.content ?: ""

            when {
                front.isBlank() -> validationErrors.add(
                    BatchError(index, BusinessErrorCodes.INVALID_FRONT, "第 ${index + 1} 张卡片 front 不能为空")
                )
                back.isBlank() -> validationErrors.add(
                    BatchError(index, BusinessErrorCodes.INVALID_BACK, "第 ${index + 1} 张卡片 back 不能为空")
                )
                front.length > 10000 -> validationErrors.add(
                    BatchError(index, BusinessErrorCodes.INVALID_FRONT, "第 ${index + 1} 张卡片 front 过长")
                )
                back.length > 10000 -> validationErrors.add(
                    BatchError(index, BusinessErrorCodes.INVALID_BACK, "第 ${index + 1} 张卡片 back 过长")
                )
                else -> {
                    val tags = noteObj["tags"]?.jsonArray?.mapNotNull {
                        val s = it.jsonPrimitive.content.trim()
                        if (s.isNotBlank()) s else null
                    }?.distinct() ?: emptyList()
                    validNotes.add(SingleNoteRequest(front = front, back = back, tags = tags))
                }
            }
        }

        if (validNotes.isEmpty() && validationErrors.isNotEmpty()) {
            val result = JsonObject(
                mapOf(
                    "requested" to JsonPrimitive(notesArray.size),
                    "succeeded" to JsonPrimitive(0),
                    "failed" to JsonPrimitive(validationErrors.size),
                    "noteIds" to JsonArray(emptyList()),
                    "errors" to JsonArray(validationErrors.map { err ->
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
            return McpToolCallResult(content = listOf(McpToolContent(text = result.toString())))
        }

        return try {
            val request = AddBasicNotesRequest(deck = deck.trim(), notes = validNotes)
            val result = ankiRepository.addBasicNotes(request)

            // 合并预校验错误和 API 返回的错误
            val allErrors = validationErrors + result.errors

            val json = JsonObject(
                mapOf(
                    "requested" to JsonPrimitive(notesArray.size),
                    "succeeded" to JsonPrimitive(result.succeeded),
                    "failed" to JsonPrimitive(allErrors.size),
                    "noteIds" to JsonArray(result.noteIds.map { JsonPrimitive(it) }),
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
            McpToolCallResult(content = listOf(McpToolContent(text = json.toString())))
        } catch (e: AnkiDroidNotInstalledException) {
            throwToolError(BusinessErrorCodes.ANKIDROID_NOT_INSTALLED, e.message ?: "AnkiDroid 未安装")
        } catch (e: AnkiPermissionDeniedException) {
            throwToolError(BusinessErrorCodes.ANKI_PERMISSION_DENIED, e.message ?: "AnkiDroid 权限未授权")
        } catch (e: ModelNotFoundException) {
            throwToolError(BusinessErrorCodes.MODEL_NOT_FOUND, e.message ?: "笔记类型未找到")
        } catch (e: Exception) {
            throwToolError(BusinessErrorCodes.INTERNAL_ERROR, e.message ?: "内部错误")
        }
    }
}
