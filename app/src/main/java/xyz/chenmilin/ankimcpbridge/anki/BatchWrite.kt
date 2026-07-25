package xyz.chenmilin.ankimcpbridge.anki

/**
 * 通用批量写入的纯函数协调器（v0.2.1）。
 *
 * 设计目标：把“分组 / 数量汇总 / 移组数量决策 / 失败统计”从真实 [AnkiDroidRepository]
 * 与 [FakeAnkiRepository] 中抽离出来，做成**不依赖真实 ContentResolver**、可被单元测试
 * 直接覆盖的逻辑。真实实现用 [android.content.ContentValues] 作为值类型 `T`，测试可用任意
 * 普通类型（如 `String`）作为 `T`，从而绕开 Android 框架依赖。
 *
 * 这些函数同时被真实实现与 Fake 实现调用，避免“测试通过但真实设备逻辑仍错”的语义漂移。
 */

/**
 * 单个笔记类型（model）的批量写入计划。
 *
 * @param T 值类型：真实实现为 [android.content.ContentValues]，测试可用任意类型。
 * @param noteTypeId 笔记类型 ID
 * @param values 该模型下待插入的条目（已构造好 ContentValues）
 * @param originalIndexes 这些条目对应的**原始请求下标**，仅用于日志记录/调试
 */
internal data class ModelBatchPlan<T>(
    val noteTypeId: Long,
    val values: List<T>,
    val originalIndexes: List<Int>
)

/** 逐个模型执行插入后的汇总结果。 */
internal data class BatchInsertSummary(
    /** key=noteTypeId，value=该模型实际插入成功的数量（已 coerce 为 >=0）。 */
    val insertedByModel: Map<Long, Int>,
    /** 插入级错误：整组失败（[AnkiErrors.BATCH_FAILED]）或部分失败（[AnkiErrors.PARTIAL_FAILURE]）。 */
    val insertErrors: List<BatchError>,
    /** 所有模型实际插入数量之和。 */
    val totalInserted: Int
)

/**
 * 按笔记类型分组执行批量插入的纯函数协调器。
 *
 * 流程：
 * 1. 遍历 [groups] 中每个模型；
 * 2. 调用可注入的 [insert] 函数（真实实现 = 一次 `bulkInsert`，Fake = 内存写入）拿到返回值；
 * 3. [insert] 返回 `< 0` 视为**整组插入失败**（该模型贡献 0 条，记 [AnkiErrors.BATCH_FAILED]）；
 * 4. `0 <= 结果 < values.size` 视为**部分失败**（记 [AnkiErrors.PARTIAL_FAILURE]，并带计划/成功数量）；
 * 5. 某个模型失败**不会**阻断其他模型；
 * 6. 返回各模型实际插入数量与插入级错误。
 *
 * 注意：实际“移动卡片到牌组”由调用方依据 [BatchInsertSummary.insertedByModel] 完成，
 * 即每个模型只移动自己**实际插入**的数量，避免误移写入前的旧卡片。
 */
internal fun <T> executeBatchInsert(
    groups: Map<Long, ModelBatchPlan<T>>,
    insert: (noteTypeId: Long, values: List<T>) -> Int
): BatchInsertSummary {
    val insertedByModel = LinkedHashMap<Long, Int>()
    val insertErrors = mutableListOf<BatchError>()
    var totalInserted = 0
    for ((noteTypeId, plan) in groups) {
        val raw = insert(noteTypeId, plan.values)
        if (raw < 0) {
            // 整组插入失败：该模型贡献 0 条成功。
            insertedByModel[noteTypeId] = 0
            insertErrors.add(
                BatchError(
                    -1, AnkiErrors.BATCH_FAILED,
                    "noteTypeId=$noteTypeId：批量插入失败（bulkInsert 返回 $raw）"
                )
            )
            continue
        }
        val inserted = raw
        insertedByModel[noteTypeId] = inserted
        totalInserted += inserted
        if (inserted < plan.values.size) {
            // 部分失败：计划数 > 实际成功数。
            insertErrors.add(
                BatchError(
                    -1, AnkiErrors.PARTIAL_FAILURE,
                    "noteTypeId=$noteTypeId：计划插入${plan.values.size}条，实际成功${inserted}条"
                )
            )
        }
    }
    return BatchInsertSummary(insertedByModel, insertErrors, totalInserted)
}

/**
 * 批量失败数的统一定义：
 *
 *     failed = (requested - succeeded).coerceAtLeast(0)
 *
 * 其中 [requested] 为用户提交总条数，[succeeded] 为 ContentProvider 实际插入成功总条数。
 * 这样能正确覆盖“全部在预校验阶段失败（submitted=0 但 failed=requested）”的情况，
 * 而不再用 `submitted - inserted`（会遗漏预校验失败项）。
 */
internal fun calculateBatchFailed(requested: Int, succeeded: Int): Int =
    (requested - succeeded).coerceAtLeast(0)

/**
 * 批量写入的持久化验证（best-effort）：每个模型的“实际读回数量”必须 >= “期望插入数量”。
 *
 * @param insertedByModel key=noteTypeId，value=该模型实际插入成功数量
 * @param readCounts key=noteTypeId，value=ContentProvider 实际能读回的该模型最新笔记数量
 * @return 所有模型都达到期望数量时为 true；任一不足或 [insertedByModel] 为空时为 false
 */
internal fun checkBatchPersisted(
    insertedByModel: Map<Long, Int>,
    readCounts: Map<Long, Int>
): Boolean {
    if (insertedByModel.isEmpty()) return false
    for ((modelId, expected) in insertedByModel) {
        if (expected <= 0) continue
        val found = readCounts[modelId] ?: 0
        if (found < expected) return false
    }
    return true
}

/**
 * 工具层 `isError` 判定（v0.2.1 统一）：
 * 1. 存在任意失败数 → 错误；
 * 2. 存在任意错误（含预校验错误）→ 错误；
 * 3. 有写入成功但持久化验证失败 → 错误；
 * 4. 仅 [BatchAddGenericResult.refreshNotified]=false 不单独导致错误（刷新是 best-effort）。
 */
internal fun shouldMarkBatchToolError(result: BatchAddGenericResult): Boolean =
    result.failed > 0 ||
        result.errors.isNotEmpty() ||
        (result.succeeded > 0 && !result.persisted)
