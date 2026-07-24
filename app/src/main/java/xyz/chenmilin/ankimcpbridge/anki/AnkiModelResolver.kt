package xyz.chenmilin.ankimcpbridge.anki

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import com.ichi2.anki.FlashCardsContract
import org.json.JSONArray

/**
 * 解析 AnkiDroid 中的 Basic 笔记类型（包含 Front + Back 两个字段）。
 *
 * 直接基于官方 [FlashCardsContract] 实现：
 * - 通过 [FlashCardsContract.Model.CONTENT_URI] 遍历所有笔记类型；
 * - 字段名来自 [FlashCardsContract.Model.FIELD_NAMES]（JSON 数组字符串，例如 ["Front","Back"]）；
 * - 若找不到合适的模型，则尝试创建 "MCP Basic" 模型。
 */
object AnkiModelResolver {

    const val FIELD_FRONT = "Front"
    const val FIELD_BACK = "Back"
    private const val MCP_BASIC_MODEL_NAME = "MCP Basic"

    /**
     * 查找一个字段为 [Front, Back] 的笔记类型 ID。
     * 找不到时尝试创建 "MCP Basic" 模型；创建失败抛出 [ModelNotFoundException]。
     */
    fun resolveBasicModelId(context: Context): Long {
        context.contentResolver.query(
            FlashCardsContract.Model.CONTENT_URI, null, null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Model._ID)
            val fldsIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Model.FIELD_NAMES)
            while (cursor.moveToNext()) {
                val modelId = cursor.getLong(idIdx)
                val raw = cursor.getString(fldsIdx) ?: continue
                val fields = parseFieldNames(raw)
                if (fields.size == 2 &&
                    fields[0].trim().equals(FIELD_FRONT, ignoreCase = true) &&
                    fields[1].trim().equals(FIELD_BACK, ignoreCase = true)
                ) {
                    return modelId
                }
            }
        }
        return createBasicModel(context, MCP_BASIC_MODEL_NAME)
            ?: throw ModelNotFoundException(
                "无法创建 Basic 笔记类型。请在 AnkiDroid 中确认存在包含 Front 和 Back 字段的基础笔记类型。"
            )
    }

    /** 创建一个只包含 Front/Back 两个字段的笔记类型，返回其 ID。 */
    fun createBasicModel(context: Context, name: String): Long? {
        val values = ContentValues().apply {
            put(FlashCardsContract.Model.NAME, name)
            put(
                FlashCardsContract.Model.FIELD_NAMES,
                JSONArray(listOf(FIELD_FRONT, FIELD_BACK)).toString()
            )
        }
        val uri = context.contentResolver.insert(FlashCardsContract.Model.CONTENT_URI, values)
            ?: return null
        return uri.lastPathSegment?.toLongOrNull()
    }

    /** 将 FIELD_NAMES 的原始字符串解析为字段名数组（兼容 JSON 数组与 \u001f 分隔两种格式）。 */
    internal fun parseFieldNames(raw: String): Array<String> {
        return try {
            val arr = JSONArray(raw)
            Array(arr.length()) { i -> arr.getString(i) }
        } catch (e: Exception) {
            raw.split("\u001f").toTypedArray()
        }
    }
}

class ModelNotFoundException(message: String) : Exception(message)
