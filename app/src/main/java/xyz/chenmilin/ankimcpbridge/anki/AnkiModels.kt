package xyz.chenmilin.ankimcpbridge.anki

data class AnkiDeck(
    val id: Long,
    val name: String
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
