package xyz.chenmilin.ankimcpbridge.server.tools

import kotlinx.serialization.json.*
import xyz.chenmilin.ankimcpbridge.anki.*
import xyz.chenmilin.ankimcpbridge.server.*

class ListNoteTypesTool(private val ankiRepository: AnkiRepository) : McpTool {

    override val definition = McpToolDef(
        name = "list_note_types",
        description = "列出本机 AnkiDroid 中所有可用的笔记类型（Note Type / Model），包含每个类型的 ID、名称、有序字段名列表、类型（normal/cloze/unknown）与卡片模板数量。在写入任意笔记类型前先调用本工具，获取 noteTypeId 与字段名，再传给 add_note / add_notes。",
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
            val types = ankiRepository.listNoteTypes()
            val json = JsonObject(
                mapOf(
                    "count" to JsonPrimitive(types.size),
                    "noteTypes" to JsonArray(
                        types.map { t ->
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive(t.id),
                                    "name" to JsonPrimitive(t.name),
                                    "type" to JsonPrimitive(t.type),
                                    "cardTemplateCount" to JsonPrimitive(t.cardTemplateCount),
                                    "fields" to JsonArray(t.fields.map { JsonPrimitive(it) })
                                )
                            )
                        }
                    )
                )
            )
            McpToolCallResult(content = listOf(McpToolContent(text = json.toString())))
        } catch (e: AnkiDroidNotInstalledException) {
            businessError(BusinessErrorCodes.ANKIDROID_NOT_INSTALLED, e.message ?: "AnkiDroid 未安装")
        } catch (e: AnkiPermissionDeniedException) {
            businessError(BusinessErrorCodes.ANKI_PERMISSION_DENIED, e.message ?: "AnkiDroid 权限未授权")
        } catch (e: Exception) {
            businessError(BusinessErrorCodes.INTERNAL_ERROR, e.message ?: "内部错误")
        }
    }
}
