package xyz.chenmilin.ankimcpbridge.server.tools

import kotlinx.serialization.json.*
import xyz.chenmilin.ankimcpbridge.anki.*
import xyz.chenmilin.ankimcpbridge.server.*

class AddBasicNoteTool(private val ankiRepository: AnkiRepository) : McpTool {

    override val definition = McpToolDef(
        name = "add_basic_note",
        description = "向用户 AnkiDroid 的指定牌组添加一张 Basic 类型（正面/背面）卡片。自动确保牌组存在。当对话中出现了值得记忆的知识点时，调用本工具把该知识点固化为一张复习卡片（front=问题/提示，back=答案/解释）。",
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
                        "front" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("卡片正面内容（问题）")
                            )
                        ),
                        "back" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("卡片背面内容（答案）")
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
                    listOf(
                        JsonPrimitive("deck"),
                        JsonPrimitive("front"),
                        JsonPrimitive("back")
                    )
                )
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val deck = arguments?.get("deck")?.jsonPrimitive?.content
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: deck")
        val front = arguments["front"]?.jsonPrimitive?.content
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: front")
        val back = arguments["back"]?.jsonPrimitive?.content
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: back")

        // 参数校验（业务级错误 -> 工具结果 isError=true）
        if (front.isBlank()) return businessError(BusinessErrorCodes.INVALID_FRONT, "front 不能为空")
        if (back.isBlank()) return businessError(BusinessErrorCodes.INVALID_BACK, "back 不能为空")
        if (front.length > 10000) return businessError(BusinessErrorCodes.INVALID_FRONT, "front 内容过长（最多10000字符）")
        if (back.length > 10000) return businessError(BusinessErrorCodes.INVALID_BACK, "back 内容过长（最多10000字符）")

        val tags = parseTags(arguments)

        return try {
            val request = AddBasicNoteRequest(deck = deck.trim(), front = front, back = back, tags = tags)
            val result = ankiRepository.addBasicNote(request)
            val json = JsonObject(
                mapOf(
                    "success" to JsonPrimitive(result.success),
                    "noteId" to (result.noteId?.let { JsonPrimitive(it) } ?: JsonNull),
                    "deck" to JsonPrimitive(result.deck)
                )
            )
            McpToolCallResult(content = listOf(McpToolContent(text = json.toString())))
        } catch (e: AnkiDroidNotInstalledException) {
            businessError(BusinessErrorCodes.ANKIDROID_NOT_INSTALLED, e.message ?: "AnkiDroid 未安装")
        } catch (e: AnkiPermissionDeniedException) {
            businessError(BusinessErrorCodes.ANKI_PERMISSION_DENIED, e.message ?: "AnkiDroid 权限未授权")
        } catch (e: ModelNotFoundException) {
            businessError(BusinessErrorCodes.MODEL_NOT_FOUND, e.message ?: "笔记类型未找到")
        } catch (e: AddNoteException) {
            businessError(BusinessErrorCodes.ADD_NOTE_FAILED, e.message ?: "添加卡片失败")
        } catch (e: Exception) {
            businessError(BusinessErrorCodes.INTERNAL_ERROR, e.message ?: "内部错误")
        }
    }

    private fun parseTags(arguments: JsonObject): List<String> {
        val tagsArray = arguments["tags"]?.jsonArray ?: return emptyList()
        return tagsArray.mapNotNull {
            val s = it.jsonPrimitive.content.trim()
            if (s.isNotBlank()) s else null
        }.distinct()
    }
}
