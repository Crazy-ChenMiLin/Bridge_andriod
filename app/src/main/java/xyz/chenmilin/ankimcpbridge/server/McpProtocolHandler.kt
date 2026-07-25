package xyz.chenmilin.ankimcpbridge.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import xyz.chenmilin.ankimcpbridge.anki.AnkiRepository
import xyz.chenmilin.ankimcpbridge.logging.AppLogRepository
import xyz.chenmilin.ankimcpbridge.server.tools.*

/**
 * MCP Streamable HTTP 协议处理器。
 * 处理 JSON-RPC 2.0 请求，路由到对应方法。
 * 自行实现 MCP 必需的最小协议子集（见 docs/mcp-protocol.md 中关于 MCP Kotlin SDK 选型的说明）。
 *
 * 设计说明（修复 v0.1.0）：
 * - 不再维护全局 `initialized` 可变标志；tools/list 与 tools/call 无需先 initialize 即可调用。
 * - 业务错误（AnkiDroid 未安装、权限不足、添加失败等）以工具结果返回，并设置 `isError = true`，
 *   而非 JSON-RPC error，符合 MCP 工具错误语义。
 * - initialize 支持 protocolVersion 协商：客户端声明支持的版本，服务端回协商后的版本。
 */
class McpProtocolHandler(
    private val ankiRepository: AnkiRepository,
    private val logRepo: AppLogRepository
) {
    private val toolRegistry = ToolRegistry()

    init {
        registerTools()
    }

    private fun registerTools() {
        // 基础工具（连接/牌组）
        toolRegistry.register(BridgeStatusTool(ankiRepository))
        toolRegistry.register(ListDecksTool(ankiRepository))
        toolRegistry.register(EnsureDeckTool(ankiRepository))
        // 旧 Basic 写入工具（保留兼容）
        toolRegistry.register(AddBasicNoteTool(ankiRepository))
        toolRegistry.register(AddBasicNotesTool(ankiRepository))
        // v0.2.0 通用笔记类型读取与写入工具
        toolRegistry.register(ListNoteTypesTool(ankiRepository))
        toolRegistry.register(GetNoteTypeTool(ankiRepository))
        toolRegistry.register(AddNoteTool(ankiRepository))
        toolRegistry.register(AddNotesTool(ankiRepository))
        // PC Anki MCP compatible aliases for actions that map cleanly to
        // AnkiDroid's public ContentProvider API.
        registerPcCompatibleTools()
    }

    private fun registerPcCompatibleTools() {
        toolRegistry.register(PcListDecksTool(ankiRepository))
        toolRegistry.register(PcDeckNamesTool(ankiRepository))
        toolRegistry.register(PcDeckNamesAndIdsTool(ankiRepository))
        toolRegistry.register(PcCreateDeckTool(ankiRepository))
        toolRegistry.register(PcModelNamesTool(ankiRepository))
        toolRegistry.register(PcModelNamesAndIdsTool(ankiRepository))
        toolRegistry.register(PcModelFieldNamesTool(ankiRepository))
        toolRegistry.register(PcAddNoteTool(ankiRepository))
        toolRegistry.register(PcAddNotesTool(ankiRepository))
        toolRegistry.register(PcCanAddNotesTool(ankiRepository))
        toolRegistry.register(PcFindNotesTool(ankiRepository))
        toolRegistry.register(PcFindCardsTool(ankiRepository))
        toolRegistry.register(PcNotesInfoTool(ankiRepository))
        toolRegistry.register(PcCardsInfoTool(ankiRepository))
        toolRegistry.register(PcCardsToNotesTool(ankiRepository))
        toolRegistry.register(PcGetDecksTool(ankiRepository))
        toolRegistry.register(PcSuspendTool(ankiRepository))
        toolRegistry.register(PcAreSuspendedTool(ankiRepository))
        toolRegistry.register(PcAreDueTool(ankiRepository))
        toolRegistry.register(PcGetIntervalsTool(ankiRepository))
        toolRegistry.register(PcUpdateNoteFieldsTool(ankiRepository))
        toolRegistry.register(PcGetTagsTool(ankiRepository))
        toolRegistry.register(PcAddTagsTool(ankiRepository))
        toolRegistry.register(PcRemoveTagsTool(ankiRepository))
        toolRegistry.register(PcReplaceTagsTool(ankiRepository))
        toolRegistry.register(PcModelTemplatesTool(ankiRepository))
        toolRegistry.register(PcModelStylingTool(ankiRepository))
        toolRegistry.register(PcGetCardsTool(ankiRepository))
        toolRegistry.register(PcGetDueCardsTool(ankiRepository))
        toolRegistry.register(PcPresentCardTool(ankiRepository))
        toolRegistry.register(PcChangeDeckTool(ankiRepository))
        toolRegistry.register(PcRateCardTool(ankiRepository))
        toolRegistry.register(PcDeckStatsTool(ankiRepository))
        toolRegistry.register(PcCollectionStatsTool(ankiRepository))
    }

    fun handleRequest(body: String): String {
        val request: JsonRpcRequest = try {
            val json = Json.parseToJsonElement(body).jsonObject
            parseRequest(json)
        } catch (e: Exception) {
            return buildErrorResponse(null, McpErrorCodes.PARSE_ERROR, "Parse error: ${e.message}")
        }

        // 记录每次 MCP 请求的具体方法（不记录参数内容，避免泄露 Token 或卡片正文）。
        logRepo.info("收到 MCP 请求: ${request.method}")

        return try {
            when (request.method) {
                "initialize" -> handleInitialize(request)
                "notifications/initialized" -> handleNotificationInitialized()
                "ping" -> handlePing(request)
                "tools/list" -> handleToolsList(request)
                "tools/call" -> runBlocking { handleToolsCall(request) }
                else -> buildErrorResponse(
                    request.id, McpErrorCodes.METHOD_NOT_FOUND,
                    "Method not found: ${request.method}"
                )
            }
        } catch (e: ToolErrorException) {
            // 结构性参数错误 -> JSON-RPC error
            buildErrorResponse(
                request.id, McpErrorCodes.INVALID_PARAMS,
                e.message,
                JsonObject(mapOf("code" to JsonPrimitive(e.errorCode)))
            )
        } catch (e: Exception) {
            logRepo.error("处理请求异常: ${e.message}")
            buildErrorResponse(
                request.id, McpErrorCodes.INTERNAL_ERROR,
                "Internal error: ${e.message}"
            )
        }
    }

    private fun parseRequest(json: JsonObject): JsonRpcRequest {
        val method = json["method"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'method'")
        val id = json["id"]
        val params = json["params"]?.jsonObject
        return JsonRpcRequest(id = id, method = method, params = params)
    }

    private fun handleInitialize(request: JsonRpcRequest): String {
        logRepo.info("MCP 客户端已初始化")

        val clientVersion = request.params?.get("protocolVersion")?.jsonPrimitive?.content
        val negotiated = if (clientVersion == SUPPORTED_PROTOCOL_VERSION) {
            clientVersion
        } else {
            SUPPORTED_PROTOCOL_VERSION
        }

        val result = JsonObject(
            mapOf(
                "protocolVersion" to JsonPrimitive(negotiated),
                "capabilities" to JsonObject(mapOf("tools" to JsonObject(emptyMap()))),
                "serverInfo" to JsonObject(
                    mapOf(
                        "name" to JsonPrimitive("ankidroid-mcp-bridge"),
                        "version" to JsonPrimitive(SERVER_VERSION)
                    )
                )
            )
        )
        return buildSuccessResponse(request.id, result)
    }

    private fun handleNotificationInitialized(): String {
        logRepo.info("收到 notifications/initialized")
        return ""
    }

    private fun handlePing(request: JsonRpcRequest): String {
        return buildSuccessResponse(request.id, JsonObject(emptyMap()))
    }

    private fun handleToolsList(request: JsonRpcRequest): String {
        val tools = toolRegistry.listDefinitions().map { def ->
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(def.name),
                    "description" to JsonPrimitive(def.description),
                    "inputSchema" to def.inputSchema
                )
            )
        }
        val result = JsonObject(mapOf("tools" to JsonArray(tools)))
        return buildSuccessResponse(request.id, result)
    }

    private suspend fun handleToolsCall(request: JsonRpcRequest): String {
        val params = request.params ?: return buildErrorResponse(
            request.id, McpErrorCodes.INVALID_PARAMS, "Missing params"
        )

        val toolName = params["name"]?.jsonPrimitive?.content
            ?: return buildErrorResponse(
                request.id, McpErrorCodes.INVALID_PARAMS, "Missing tool name",
                JsonObject(mapOf("code" to JsonPrimitive(BusinessErrorCodes.TOOL_NOT_FOUND)))
            )

        val tool = toolRegistry.getTool(toolName)
            ?: return buildErrorResponse(
                request.id, McpErrorCodes.METHOD_NOT_FOUND,
                "Tool not found: $toolName",
                JsonObject(mapOf("code" to JsonPrimitive(BusinessErrorCodes.TOOL_NOT_FOUND)))
            )

        logRepo.info("调用工具: $toolName")

        return try {
            val arguments = params["arguments"]?.jsonObject
            val callResult = tool.call(arguments)

            val result = JsonObject(
                mapOf(
                    "content" to JsonArray(
                        callResult.content.map { content ->
                            JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive(content.type),
                                    "text" to JsonPrimitive(content.text)
                                )
                            )
                        }
                    ),
                    "isError" to JsonPrimitive(callResult.isError)
                )
            )
            buildSuccessResponse(request.id, result)
        } catch (e: ToolErrorException) {
            buildErrorResponse(
                request.id, McpErrorCodes.INVALID_PARAMS,
                e.message,
                JsonObject(mapOf("code" to JsonPrimitive(e.errorCode)))
            )
        } catch (e: Exception) {
            logRepo.error("工具 $toolName 执行失败: ${e.message}")
            buildErrorResponse(
                request.id, McpErrorCodes.INTERNAL_ERROR,
                "Tool execution failed: ${e.message}",
                JsonObject(mapOf("code" to JsonPrimitive(BusinessErrorCodes.INTERNAL_ERROR)))
            )
        }
    }

    private fun buildSuccessResponse(id: JsonElement?, result: JsonElement): String {
        val responseObj = mutableMapOf<String, JsonElement>(
            "jsonrpc" to JsonPrimitive("2.0"),
            "result" to result
        )
        if (id != null) responseObj["id"] = id
        return JsonObject(responseObj).toString()
    }

    private fun buildErrorResponse(
        id: JsonElement?,
        code: Int,
        message: String,
        data: JsonElement? = null
    ): String {
        val errorObj = mutableMapOf<String, JsonElement>(
            "code" to JsonPrimitive(code),
            "message" to JsonPrimitive(message)
        )
        if (data != null) errorObj["data"] = data

        val responseObj = mutableMapOf<String, JsonElement>(
            "jsonrpc" to JsonPrimitive("2.0"),
            "error" to JsonObject(errorObj)
        )
        if (id != null) responseObj["id"] = id
        return JsonObject(responseObj).toString()
    }

    companion object {
        const val SUPPORTED_PROTOCOL_VERSION = "2024-11-05"
        const val SERVER_VERSION = "0.2.3"
    }
}
