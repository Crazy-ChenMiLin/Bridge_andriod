package xyz.chenmilin.ankimcpbridge.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 工具接口：每个 MCP 工具实现此接口。
 */
interface McpTool {
    val definition: McpToolDef
    suspend fun call(arguments: JsonObject?): McpToolCallResult
}

/**
 * 工具注册中心。
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, McpTool>()

    fun register(tool: McpTool) {
        tools[tool.definition.name] = tool
    }

    fun getTool(name: String): McpTool? = tools[name]

    fun listDefinitions(): List<McpToolDef> = tools.values.map { it.definition }

    fun listNames(): Set<String> = tools.keys.toSet()
}
