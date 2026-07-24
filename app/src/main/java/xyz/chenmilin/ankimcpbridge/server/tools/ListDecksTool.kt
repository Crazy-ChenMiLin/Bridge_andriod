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
            businessError(BusinessErrorCodes.ANKIDROID_NOT_INSTALLED, e.message ?: "AnkiDroid 未安装")
        } catch (e: AnkiPermissionDeniedException) {
            businessError(BusinessErrorCodes.ANKI_PERMISSION_DENIED, e.message ?: "AnkiDroid 权限未授权")
        } catch (e: Exception) {
            businessError(BusinessErrorCodes.ANKI_API_UNAVAILABLE, e.message ?: "AnkiDroid API 不可用")
        }
    }
}

/**
 * 结构性参数错误：抛出后由协议层转换为 JSON-RPC INVALID_PARAMS 错误。
 * 用于缺少必填参数等“请求本身不合法”的场景。
 */
internal fun throwToolError(code: String, message: String): Nothing {
    throw ToolErrorException(code, message)
}

/**
 * 业务错误：以工具结果返回，并设置 [McpToolCallResult.isError] = true。
 * 用于 AnkiDroid 未安装、权限不足、添加失败、字段校验不通过等场景。
 */
internal fun businessError(code: String, message: String): McpToolCallResult {
    val json = JsonObject(
        mapOf(
            "code" to JsonPrimitive(code),
            "message" to JsonPrimitive(message)
        )
    )
    return McpToolCallResult(
        content = listOf(McpToolContent(text = json.toString())),
        isError = true
    )
}

class ToolErrorException(val errorCode: String, override val message: String) : Exception(message)
