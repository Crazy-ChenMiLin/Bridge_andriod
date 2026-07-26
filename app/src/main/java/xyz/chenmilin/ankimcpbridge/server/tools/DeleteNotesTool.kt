package xyz.chenmilin.ankimcpbridge.server.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import xyz.chenmilin.ankimcpbridge.anki.AnkiDroidNotInstalledException
import xyz.chenmilin.ankimcpbridge.anki.AnkiPermissionDeniedException
import xyz.chenmilin.ankimcpbridge.anki.AnkiRepository
import xyz.chenmilin.ankimcpbridge.server.*

class DeleteNotesTool(private val ankiRepository: AnkiRepository) : McpTool {
    override val definition = McpToolDef(
        name = "delete_notes",
        description = "Delete notes by explicit note IDs. Deleting a note also removes its cards. Use findNotes/notesInfo first and pass only confirmed noteIds; this tool does not delete by title or search text.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "noteIds" to JsonObject(mapOf("type" to JsonPrimitive("array"))),
                        "notes" to JsonObject(mapOf("type" to JsonPrimitive("array")))
                    )
                ),
                "required" to JsonArray(emptyList())
            )
        )
    )

    override suspend fun call(arguments: JsonObject?): McpToolCallResult {
        val raw = arguments?.get("noteIds")?.jsonArray ?: arguments?.get("notes")?.jsonArray
            ?: throwToolError(BusinessErrorCodes.INVALID_ARGUMENT, "missing parameter: noteIds")
        if (raw.isEmpty()) {
            return businessError(BusinessErrorCodes.INVALID_ARGUMENT, "noteIds must not be empty")
        }
        if (raw.size > 100) {
            return businessError(BusinessErrorCodes.BATCH_TOO_LARGE, "delete_notes supports at most 100 note IDs per call")
        }
        val ids = raw.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }
            .filter { it > 0 }
            .distinct()
        if (ids.isEmpty()) {
            return businessError(BusinessErrorCodes.INVALID_ARGUMENT, "noteIds must contain positive integers")
        }

        return try {
            val deleted = ankiRepository.deleteNotes(ids)
            val json = JsonObject(
                mapOf(
                    "requested" to JsonPrimitive(ids.size),
                    "deleted" to JsonPrimitive(deleted),
                    "missing" to JsonPrimitive((ids.size - deleted).coerceAtLeast(0)),
                    "noteIds" to JsonArray(ids.map { JsonPrimitive(it) })
                )
            )
            McpToolCallResult(
                content = listOf(McpToolContent(text = json.toString())),
                isError = deleted != ids.size
            )
        } catch (e: AnkiDroidNotInstalledException) {
            businessError(BusinessErrorCodes.ANKIDROID_NOT_INSTALLED, e.message ?: "AnkiDroid not installed")
        } catch (e: AnkiPermissionDeniedException) {
            businessError(BusinessErrorCodes.ANKI_PERMISSION_DENIED, e.message ?: "AnkiDroid permission denied")
        } catch (e: Exception) {
            businessError(BusinessErrorCodes.INTERNAL_ERROR, e.message ?: "internal error")
        }
    }
}
