package xyz.chenmilin.ankimcpbridge.server.tools

import kotlinx.serialization.json.*
import xyz.chenmilin.ankimcpbridge.anki.*
import xyz.chenmilin.ankimcpbridge.server.*

class EnsureDeckTool(private val ankiRepository: AnkiRepository) : McpTool {

    override val definition = McpToolDef(
        name = "ensure_deck",
        description = "确保指定牌组存在。如果不存在则创建，已存在则返回已有牌组。",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "name" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("牌组名称，最多200字符")
                            )
                        )
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("name")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val name = arguments?.get("name")?.jsonPrimitive?.content
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少参数: name")

        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "牌组名称不能为空")
        }
        if (trimmed.length > 200) {
            throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "牌组名称过长（最多200字符）")
        }

        return try {
            val existingDecks = ankiRepository.listDecks()
            val existing = existingDecks.find { it.name.equals(trimmed, ignoreCase = true) }
            if (existing != null) {
                val result = JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(existing.id),
                        "name" to JsonPrimitive(existing.name),
                        "created" to JsonPrimitive(false)
                    )
                )
                return McpToolCallResult(content = listOf(McpToolContent(text = result.toString())))
            }

            val deck = ankiRepository.ensureDeck(trimmed)
            val result = JsonObject(
                mapOf(
                    "id" to JsonPrimitive(deck.id),
                    "name" to JsonPrimitive(deck.name),
                    "created" to JsonPrimitive(true)
                )
            )
            McpToolCallResult(content = listOf(McpToolContent(text = result.toString())))
        } catch (e: AnkiDroidNotInstalledException) {
            throwToolError(BusinessErrorCodes.ANKIDROID_NOT_INSTALLED, e.message ?: "AnkiDroid 未安装")
        } catch (e: AnkiPermissionDeniedException) {
            throwToolError(BusinessErrorCodes.ANKI_PERMISSION_DENIED, e.message ?: "AnkiDroid 权限未授权")
        } catch (e: DeckOperationException) {
            throwToolError(BusinessErrorCodes.DECK_OPERATION_FAILED, e.message ?: "牌组操作失败")
        } catch (e: Exception) {
            throwToolError(BusinessErrorCodes.INTERNAL_ERROR, e.message ?: "内部错误")
        }
    }
}
