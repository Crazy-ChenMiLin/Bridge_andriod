package xyz.chenmilin.ankimcpbridge.anki

data class AnkiDeck(
    val id: Long,
    val name: String,
    /** 本次调用是否实际创建了该牌组（true=新建，false=复用已有）。 */
    val created: Boolean = false
)

data class AddBasicNoteRequest(
    val deck: String,
    val front: String,
    val back: String,
    val tags: List<String> = emptyList()
)

data class AddNoteResult(
    val success: Boolean,
    val noteId: Long?,
    val deck: String
)

data class AddBasicNotesRequest(
    val deck: String,
    val notes: List<SingleNoteRequest>
)

data class SingleNoteRequest(
    val front: String,
    val back: String,
    val tags: List<String> = emptyList()
)

/**
 * 批量添加结果。
 *
 * 字段含义：
 * - [requested]：请求中的卡片总数。
 * - [submitted]：通过预校验、真正交给批量插入（`bulkInsert`，等价官方 `AddContentApi.addNotes`）的卡片数。
 * - [succeeded]：实际插入成功的卡片数（[bulkInsert] 返回值）。
 * - [failed]：失败卡片数（[requested] - [succeeded]），包含预校验未通过与批量插入未写入的部分。
 * - [noteIds]：成功卡片的 noteId 列表。批量路径下 [bulkInsert] 不返回单个 noteId，因此恒为空。
 * - [noteIdsAvailable]：是否能信赖 [noteIds]。批量路径恒为 false（无法从批量插入取回 noteId，
 *   也就无法把卡片移动到指定牌组，这是官方批量 API 的固有限制）。
 * - [errors]：每条失败对应的错误，[BatchError.index] 指向**原始请求下标**，便于调用方回映射。
 *
 * 示例：`{ "requested": 10, "submitted": 9, "succeeded": 9, "failed": 1, "noteIds": [], "noteIdsAvailable": false, "errors": [...] }`
 */
data class BatchAddResult(
    val requested: Int,
    val submitted: Int,
    val succeeded: Int,
    val failed: Int,
    val noteIds: List<Long> = emptyList(),
    val noteIdsAvailable: Boolean = false,
    val errors: List<BatchError>
)

data class BatchError(
    val index: Int,
    val code: String,
    val message: String
)

// ───────────────────────────────────────────────────────────
// v0.2.0：通用笔记类型读取与写入
// ───────────────────────────────────────────────────────────

/**
 * 笔记类型摘要（用于列表展示）。
 *
 * @param id 笔记类型 ID
 * @param name 名称
 * @param fields 有序字段名列表
 * @param type 类型：`normal` / `cloze` / `unknown`
 * @param cardTemplateCount 卡片模板数量
 */
data class AnkiNoteTypeSummary(
    val id: Long,
    val name: String,
    val fields: List<String>,
    val type: String,
    val cardTemplateCount: Int
)

/**
 * 卡片模板（一个笔记类型可包含多个模板，对应正/反面渲染方式）。
 *
 * @param ordinal 模板序号（0..n-1）
 * @param name 模板名（如 "Card 1"）
 * @param frontTemplate 正面模板（对应 FlashCardsContract.CardTemplate.QUESTION_FORMAT）；读不到时为 null
 * @param backTemplate 背面模板（对应 CardTemplate.ANSWER_FORMAT）；读不到时为 null
 */
data class AnkiCardTemplate(
    val ordinal: Int,
    val name: String,
    val frontTemplate: String?,
    val backTemplate: String?
)

/**
 * 笔记类型详情（完整信息）。
 *
 * @param css 样式；部分 AnkiDroid 版本/接口不可读时为 null
 * @param templates 卡片模板列表；读不到时为空列表
 */
data class AnkiNoteTypeDetail(
    val id: Long,
    val name: String,
    val fields: List<String>,
    val type: String,
    val css: String?,
    val templates: List<AnkiCardTemplate>
)

/** 通用写入单张请求：按笔记类型 ID + 字段名/值写入。 */
data class AddGenericNoteRequest(
    val deck: String,
    val noteTypeId: Long,
    val fields: Map<String, String>,
    val tags: List<String> = emptyList()
)

/**
 * 通用写入单张结果。
 *
 * @param persisted 写入后回读验证是否成功（数据已落库可读回）
 * @param refreshNotified 是否已发送本地数据变更通知（[android.content.ContentResolver.notifyChange]）
 * @param deckId 本次写入实际使用的牌组 ID（由 [ensureDeck] 返回）
 * @param deckCreated 该牌组是否为本次写入**新创建**（true=新建，false=复用已有）
 */
data class AddGenericNoteResult(
    val success: Boolean,
    val noteId: Long?,
    val deck: String,
    val noteTypeId: Long,
    val persisted: Boolean,
    val refreshNotified: Boolean,
    val deckId: Long = 0L,
    val deckCreated: Boolean = false
)

/** 批量通用写入中的单条请求项（每项可指定自己的笔记类型）。 */
data class GenericNoteItem(
    val noteTypeId: Long,
    val fields: Map<String, String>,
    val tags: List<String> = emptyList()
)

/** 批量通用写入请求。 */
data class AddGenericNotesRequest(
    val deck: String,
    val notes: List<GenericNoteItem>
)

/**
 * 批量写入中通过预校验的单条计划，真实实现与 Fake 实现共享。
 *
 * @param index 原始请求下标（0-based），用于错误映射与日志
 * @param noteTypeId 笔记类型 ID
 * @param fields 已按笔记类型字段顺序排列的字段值
 * @param tags 去空去重后的标签
 */
internal data class GenericPlan(
    val index: Int,
    val noteTypeId: Long,
    val fields: List<String>,
    val tags: List<String>
)

/**
 * 批量通用写入结果。字段语义对齐 [BatchAddResult]：
 * - [requested]/[submitted]/[succeeded]/[failed]：请求/通过预校验/插入成功/失败数；
 * - [noteIds]/[noteIdsAvailable]：批量插入不返回单个 noteId，故恒空、恒 false；
 * - [errors]：每条失败带**原始下标**与原因；
 * - [persisted]：是否对成功插入的笔记完成回读验证；
 * - [refreshNotified]：是否已发送本地数据变更通知；
 * - [deckId]：本次写入实际使用的牌组 ID（由 [ensureDeck] 返回）；
 * - [deckCreated]：该牌组是否为本次写入**新创建**（true=新建，false=复用已有）。
 */
data class BatchAddGenericResult(
    val requested: Int,
    val submitted: Int,
    val succeeded: Int,
    val failed: Int,
    val noteIds: List<Long> = emptyList(),
    val noteIdsAvailable: Boolean = false,
    val errors: List<BatchError>,
    val persisted: Boolean = false,
    val refreshNotified: Boolean = false,
    val deckId: Long = 0L,
    val deckCreated: Boolean = false
)

/**
 * 把用户提供的字段（键名任意、可能含未知字段）映射到笔记类型的有序字段数组。
 *
 * 规则：
 * 1. 严格匹配优先；否则忽略大小写匹配；
 * 2. 同一有序字段忽略大小写后匹配到**多个不同输入键**时抛 [FieldMappingException]（歧义）；
 * 3. 未提供的有序字段写入空字符串（允许）；
 * 4. 输入中出现未匹配任何有序字段的键时抛 [FieldMappingException]（**不允许静默丢弃未知字段**）；
 * 5. 最终至少要有一个非空字段，否则抛 [FieldMappingException]。
 *
 * @return 与 [orderedFieldNames] 等长、按笔记类型字段顺序排列的字段数组
 * @throws FieldMappingException 字段映射失败时（未知字段 / 歧义 / 全空）
 */
internal fun mapNoteFields(
    orderedFieldNames: List<String>,
    provided: Map<String, String>
): Array<String> {
    // 输入键按小写分组，用于检测“同一字段名忽略大小写匹配到多个不同输入键”的歧义
    val providedByLower = mutableMapOf<String, MutableList<String>>()
    for (key in provided.keys) {
        providedByLower.getOrPut(key.lowercase()) { mutableListOf() }.add(key)
    }

    val usedKeys = mutableSetOf<String>()
    val result = Array(orderedFieldNames.size) { "" }

    for ((index, fieldName) in orderedFieldNames.withIndex()) {
        // 1) 严格匹配
        if (provided.containsKey(fieldName)) {
            result[index] = provided[fieldName]!!
            usedKeys.add(fieldName)
            continue
        }
        // 2) 忽略大小写匹配（输入键若已被前面的字段占用，则本字段留空，避免重复映射）
        val lower = fieldName.lowercase()
        val candidates = providedByLower[lower]
        if (candidates != null) {
            if (candidates.size > 1) {
                throw FieldMappingException(
                    AnkiErrors.AMBIGUOUS_FIELD,
                    "字段「$fieldName」忽略大小写后匹配到多个输入键：$candidates"
                )
            }
            val key = candidates[0]
            if (!usedKeys.contains(key)) {
                result[index] = provided[key]!!
                usedKeys.add(key)
            }
        }
        // 3) 否则保持空字符串
    }

    // 不允许静默丢弃未知字段
    val unknown = provided.keys - usedKeys
    if (unknown.isNotEmpty()) {
        throw FieldMappingException(
            AnkiErrors.FIELD_NOT_FOUND,
            "存在未匹配任何笔记类型字段的输入键：${unknown.joinToString(", ")}"
        )
    }

    // 至少一个非空字段
    if (result.none { it.isNotBlank() }) {
        throw FieldMappingException(
            AnkiErrors.NO_VALID_FIELD,
            "至少需要提供一个非空字段"
        )
    }

    return result
}

/** 字段映射失败（未知字段 / 歧义 / 全空）。携带业务错误码，便于工具层映射。 */
class FieldMappingException(val code: String, message: String) : Exception(message)

