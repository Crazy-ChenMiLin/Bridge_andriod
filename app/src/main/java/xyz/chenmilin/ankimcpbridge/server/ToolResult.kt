package xyz.chenmilin.ankimcpbridge.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ─── JSON-RPC 2.0 基础结构 ───

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

// ─── MCP 协议结构 ───

@Serializable
data class McpInitializeParams(
    val protocolVersion: String,
    val capabilities: JsonObject,
    val clientInfo: JsonObject? = null
)

@Serializable
data class McpInitializeResult(
    val protocolVersion: String = "2024-11-05",
    val capabilities: JsonObject = JsonObject(
        mapOf("tools" to JsonObject(emptyMap()))
    ),
    val serverInfo: JsonObject = JsonObject(
        mapOf(
            "name" to JsonPrimitive("ankidroid-mcp-bridge"),
            "version" to JsonPrimitive("0.1.1")
        )
    )
)

@Serializable
data class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

@Serializable
data class McpToolCallResult(
    val content: List<McpToolContent>,
    val isError: Boolean = false
)

@Serializable
data class McpToolContent(
    val type: String = "text",
    val text: String
)

// ─── 错误码 ───

object McpErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val SERVER_NOT_INITIALIZED = -32002
}

object BusinessErrorCodes {
    const val ANKIDROID_NOT_INSTALLED = "ANKIDROID_NOT_INSTALLED"
    const val ANKI_PERMISSION_DENIED = "ANKI_PERMISSION_DENIED"
    const val ANKI_API_UNAVAILABLE = "ANKI_API_UNAVAILABLE"
    const val MODEL_NOT_FOUND = "MODEL_NOT_FOUND"
    const val DECK_OPERATION_FAILED = "DECK_OPERATION_FAILED"
    const val ADD_NOTE_FAILED = "ADD_NOTE_FAILED"
    const val PARTIAL_FAILURE = "PARTIAL_FAILURE"
    const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
    const val INVALID_FRONT = "INVALID_FRONT"
    const val INVALID_BACK = "INVALID_BACK"
    const val BATCH_TOO_LARGE = "BATCH_TOO_LARGE"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val TOOL_NOT_FOUND = "TOOL_NOT_FOUND"
    const val PORT_IN_USE = "PORT_IN_USE"
    const val SERVER_NOT_RUNNING = "SERVER_NOT_RUNNING"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}
