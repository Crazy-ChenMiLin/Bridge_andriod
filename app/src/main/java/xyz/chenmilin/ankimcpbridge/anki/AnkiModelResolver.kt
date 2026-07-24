package xyz.chenmilin.ankimcpbridge.anki

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * 解析 AnkiDroid 中的 Basic 笔记类型。
 * 通过 ContentProvider 查询模型列表，找到包含 Front + Back 字段的两字段模型。
 */
object AnkiModelResolver {

    const val FIELD_FRONT = "Front"
    const val FIELD_BACK = "Back"
    private const val MCP_BASIC_MODEL_NAME = "MCP Basic"
    private const val CONTENT_PROVIDER_AUTHORITY = "com.ichi2.anki.flashcards"

    /**
     * 查找 Basic 模型 ID：
     * 1. 遍历所有可用模型，找包含 Front 和 Back 字段且字段数为 2 的模型。
     * 2. 若找不到，尝试创建 "MCP Basic" 模型。
     * 3. 若无法创建，抛出 ModelNotFoundException。
     */
    fun resolveBasicModelId(context: Context): Long {
        val modelsUri = Uri.parse("content://$CONTENT_PROVIDER_AUTHORITY/models/")

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(modelsUri, null, null, null, null)
            if (cursor != null && cursor.count > 0) {
                val idIdx = cursor.getColumnIndexOrThrow("_id")
                val fldsIdx = cursor.getColumnIndexOrThrow("flds")

                while (cursor.moveToNext()) {
                    val modelId = cursor.getLong(idIdx)
                    val fieldsStr = cursor.getString(fldsIdx) ?: ""

                    // AnkiDroid 字段用 \u001f 分隔
                    val fieldNames = fieldsStr.split("\u001f").map { it.trim() }

                    if (fieldNames.size == 2) {
                        val hasFront = fieldNames.any { it.equals(FIELD_FRONT, ignoreCase = true) }
                        val hasBack = fieldNames.any { it.equals(FIELD_BACK, ignoreCase = true) }
                        if (hasFront && hasBack) {
                            return modelId
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            throw AnkiPermissionDeniedException()
        } catch (e: Exception) {
            // 如果查询失败，尝试创建新模型
        } finally {
            cursor?.close()
        }

        return tryCreateMcpBasicModel(context)
    }

    private fun tryCreateMcpBasicModel(context: Context): Long {
        return try {
            val uri = Uri.parse("content://$CONTENT_PROVIDER_AUTHORITY/models/")
            val values = ContentValues().apply {
                put("name", MCP_BASIC_MODEL_NAME)
                put("flds", "$FIELD_FRONT\u001f$FIELD_BACK")
                put("css", ".card { font-family: arial; font-size: 20px; text-align: center; color: black; background-color: white; }")
                put("did", 1) // default deck
            }
            val resultUri = context.contentResolver.insert(uri, values)
            if (resultUri != null) {
                val modelId = resultUri.lastPathSegment?.toLongOrNull()
                if (modelId != null && modelId > 0) return modelId
            }
            throw ModelNotFoundException(
                "无法创建 Basic 笔记类型。请在 AnkiDroid 中手动创建一个包含 Front 和 Back 字段的基础笔记类型。"
            )
        } catch (e: ModelNotFoundException) {
            throw e
        } catch (e: Exception) {
            throw ModelNotFoundException(
                "无法找到或创建 Basic 笔记类型: ${e.message}。" +
                        "请在 AnkiDroid 中确认存在包含 Front 和 Back 字段的基础笔记类型。"
            )
        }
    }
}

class ModelNotFoundException(message: String) : Exception(message)
