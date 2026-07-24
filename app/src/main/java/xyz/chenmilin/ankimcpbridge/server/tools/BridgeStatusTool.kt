package xyz.chenmilin.ankimcpbridge.server.tools

import kotlinx.serialization.json.*
import xyz.chenmilin.ankimcpbridge.anki.AnkiRepository
import xyz.chenmilin.ankimcpbridge.server.McpTool
import xyz.chenmilin.ankimcpbridge.server.McpToolCallResult
import xyz.chenmilin.ankimcpbridge.server.McpToolContent
import xyz.chenmilin.ankimcpbridge.server.McpToolDef

class BridgeStatusTool(private val ankiRepository: AnkiRepository) : McpTool {

    override val definition = McpToolDef(
        name = "bridge_status",
        description = "获取 AnkiDroid MCP Bridge 的运行状态，包括服务状态、AnkiDroid 安装状态和权限状态。",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(emptyMap()),
                "required" to JsonArray(emptyList())
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val status = mapOf(
            "serverRunning" to JsonPrimitive(true),
            "ankiDroidInstalled" to JsonPrimitive(ankiRepository.isAnkiDroidInstalled()),
            "ankiPermissionGranted" to JsonPrimitive(ankiRepository.hasPermission()),
            "host" to JsonPrimitive("127.0.0.1"),
            "port" to JsonPrimitive(8766),
            "endpoint" to JsonPrimitive("/mcp"),
            "version" to JsonPrimitive("0.1.0")
        )
        val json = JsonObject(status)
        return McpToolCallResult(
            content = listOf(McpToolContent(text = json.toString()))
        )
    }
}
