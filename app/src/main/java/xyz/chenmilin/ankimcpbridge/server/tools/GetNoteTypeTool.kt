package xyz.chenmilin.ankimcpbridge.server.tools

import kotlinx.serialization.json.*
import xyz.chenmilin.ankimcpbridge.anki.*
import xyz.chenmilin.ankimcpbridge.server.*

class GetNoteTypeTool(private val ankiRepository: AnkiRepository) : McpTool {

    override val definition = McpToolDef(
        name = "get_note_type",
        description = "获取指定 noteTypeId 的笔记类型完整详情：有序字段名列表、类型（normal/cloze/unknown）、CSS（可能为空）以及卡片模板（正面/背面模板，可能为空列表）。在调用 add_note 前用于确认字段名拼写。",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "noteTypeId" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("integer"),
                                "description" to JsonPrimitive("笔记类型 ID（来自 list_note_types 的 id）")
                            )
                        )
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("noteTypeId")))
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val noteTypeId = arguments?.get("noteTypeId")?.jsonPrimitive?.content?.toLongOrNull()
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "缺少或非法参数: noteTypeId")

        return try {
            val detail = ankiRepository.getNoteType(noteTypeId)
            val json = JsonObject(
                mapOf(
                    "id" to JsonPrimitive(detail.id),
                    "name" to JsonPrimitive(detail.name),
                    "type" to JsonPrimitive(detail.type),
                    "css" to (detail.css?.let { JsonPrimitive(it) } ?: JsonNull),
                    "fields" to JsonArray(detail.fields.map { JsonPrimitive(it) }),
                    "templates" to JsonArray(
                        detail.templates.map { t ->
                            JsonObject(
                                mapOf(
                                    "ordinal" to JsonPrimitive(t.ordinal),
                                    "name" to JsonPrimitive(t.name),
                                    "frontTemplate" to (t.frontTemplate?.let { JsonPrimitive(it) } ?: JsonNull),
                                    "backTemplate" to (t.backTemplate?.let { JsonPrimitive(it) } ?: JsonNull)
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
        } catch (e: ModelNotFoundException) {
            businessError(BusinessErrorCodes.MODEL_NOT_FOUND, e.message ?: "笔记类型未找到")
        } catch (e: IllegalArgumentException) {
            businessError(BusinessErrorCodes.INVALID_ARGUMENT, e.message ?: "参数非法")
        } catch (e: Exception) {
            businessError(BusinessErrorCodes.INTERNAL_ERROR, e.message ?: "内部错误")
        }
    }
}
