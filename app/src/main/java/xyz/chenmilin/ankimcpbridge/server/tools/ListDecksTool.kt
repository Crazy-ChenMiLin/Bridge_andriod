package xyz.chenmilin.ankimcpbridge.server.tools

import kotlinx.serialization.json.*
import xyz.chenmilin.ankimcpbridge.anki.*
import xyz.chenmilin.ankimcpbridge.server.*

class ListDecksTool(private val ankiRepository: AnkiRepository) : McpTool {

    override val definition = McpToolDef(
        name = "list_decks",
        description = "列出 AnkiDroid 中所有牌组，按名称排序。",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(emptyMap()),
                "required" to JsonArray(emptyList())
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        return try {
            val decks = ankiRepository.listDecks()
            val deckArray = decks.map { deck ->
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(deck.id),
                        "name" to JsonPrimitive(deck.name)
                    )
                )
            }
            val result = JsonObject(
                mapOf(
                    "decks" to JsonArray(deckArray),
                    "count" to JsonPrimitive(decks.size)
                )
            )
            McpToolCallResult(content = listOf(McpToolContent(text = result.toString())))
        } catch (e: AnkiDroidNotInstalledException) {
            throwToolError(BusinessErrorCodes.ANKIDROID_NOT_INSTALLED, e.message ?: "AnkiDroid 未安装")
        } catch (e: AnkiPermissionDeniedException) {
            throwToolError(BusinessErrorCodes.ANKI_PERMISSION_DENIED, e.message ?: "AnkiDroid 权限未授权")
        } catch (e: Exception) {
            throwToolError(BusinessErrorCodes.ANKI_API_UNAVAILABLE, e.message ?: "AnkiDroid API 不可用")
        }
    }
}

internal fun throwToolError(code: String, message: String): Nothing {
    throw ToolErrorException(code, message)
}

class ToolErrorException(val errorCode: String, override val message: String) : Exception(message)
