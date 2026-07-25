package xyz.chenmilin.ankimcpbridge.anki

import org.junit.Assert.*
import org.junit.Test

class BatchWriteTest {

    // ─── calculateBatchFailed ───

    @Test
    fun `calculateBatchFailed requested 3 succeeded 2 yields 1`() {
        // requested=3, submitted=2, succeeded=2 → failed=1
        assertEquals(1, calculateBatchFailed(3, 2))
    }

    @Test
    fun `calculateBatchFailed requested 3 succeeded 3 yields 0`() {
        assertEquals(0, calculateBatchFailed(3, 3))
    }

    @Test
    fun `calculateBatchFailed all precheck failed yields requested`() {
        // requested=3, submitted=0, succeeded=0 → failed=3
        assertEquals(3, calculateBatchFailed(3, 0))
    }

    @Test
    fun `calculateBatchFailed never negative`() {
        assertEquals(0, calculateBatchFailed(0, 5))
        // 不变量：failed 恒等于 max(0, requested - succeeded)
        for (r in 0..5) for (s in 0..5) {
            assertEquals(maxOf(0, r - s), calculateBatchFailed(r, s))
        }
    }

    // ─── executeBatchInsert：分组 / 移组数量 / 失败 ───

    @Test
    fun `executeBatchInsert groups by noteTypeId and uses actual inserted count`() {
        val groups = mapOf(
            10L to ModelBatchPlan(10L, values = List(5) { "a$it" }, originalIndexes = (0..4).toList()),
            20L to ModelBatchPlan(20L, values = List(3) { "b$it" }, originalIndexes = (5..7).toList())
        )
        // 模型 10 计划 5 条，实际成功 3 条；模型 20 计划 3 条，全部成功
        val summary = executeBatchInsert(groups) { noteTypeId, _ ->
            if (noteTypeId == 10L) 3 else 3
        }
        // 关键：移组数量应使用实际插入数（3），而非计划数（5）
        assertEquals(3, summary.insertedByModel[10L])
        assertEquals(3, summary.insertedByModel[20L])
        assertEquals(6, summary.totalInserted)
        // 模型 10 触发部分失败
        assertEquals(1, summary.insertErrors.size)
        assertEquals(AnkiErrors.PARTIAL_FAILURE, summary.insertErrors[0].code)
        assertEquals(-1, summary.insertErrors[0].index)
        // 失败数 = requested - succeeded = 8 - 6 = 2（模型 10 缺 2 条）
        assertEquals(2, calculateBatchFailed(8, summary.totalInserted))
    }

    @Test
    fun `executeBatchInsert one model failure does not block another`() {
        val groups = mapOf(
            10L to ModelBatchPlan(10L, values = List(2) { "a$it" }, originalIndexes = (0..1).toList()),
            20L to ModelBatchPlan(20L, values = List(4) { "b$it" }, originalIndexes = (2..5).toList())
        )
        val summary = executeBatchInsert(groups) { noteTypeId, plans ->
            if (noteTypeId == 10L) -1 else plans.size // 模型 10 整组失败
        }
        // 失败模型贡献 0，成功模型不受影响
        assertEquals(0, summary.insertedByModel[10L])
        assertEquals(4, summary.insertedByModel[20L])
        assertEquals(4, summary.totalInserted)
        assertEquals(AnkiErrors.BATCH_FAILED, summary.insertErrors.first().code)
        assertEquals(-1, summary.insertErrors.first().index)
    }

    @Test
    fun `executeBatchInsert planned 5 inserted 3 moves only 3`() {
        val groups = mapOf(
            10L to ModelBatchPlan(10L, values = List(5) { "a$it" }, originalIndexes = (0..4).toList())
        )
        val summary = executeBatchInsert(groups) { _, _ -> 3 }
        // 传给“移组逻辑”的数量必须是实际插入数 3，而不是计划数 5
        assertEquals(3, summary.insertedByModel[10L])
        assertTrue(summary.insertErrors.any { it.code == AnkiErrors.PARTIAL_FAILURE })
    }

    // ─── checkBatchPersisted ───

    @Test
    fun `checkBatchPersisted true when all models reach expected count`() {
        val inserted = mapOf(5L to 5, 6L to 3)
        val read = mapOf(5L to 5, 6L to 3)
        assertTrue(checkBatchPersisted(inserted, read))
    }

    @Test
    fun `checkBatchPersisted false when expected 5 but found 2`() {
        val inserted = mapOf(5L to 5)
        val read = mapOf(5L to 2)
        assertFalse(checkBatchPersisted(inserted, read))
    }

    @Test
    fun `checkBatchPersisted false on empty insertedByModel`() {
        assertFalse(checkBatchPersisted(emptyMap(), emptyMap()))
    }

    @Test
    fun `checkBatchPersisted true when one model expected 0`() {
        // expected<=0 的模型被跳过，不要求读回
        val inserted = mapOf(5L to 0, 6L to 3)
        val read = mapOf(6L to 3)
        assertTrue(checkBatchPersisted(inserted, read))
    }

    // ─── shouldMarkBatchToolError ───

    private fun batchResult(
        requested: Int = 0,
        submitted: Int = 0,
        succeeded: Int = 0,
        failed: Int = 0,
        errors: List<BatchError> = emptyList(),
        persisted: Boolean = true,
        refreshNotified: Boolean = true
    ) = BatchAddGenericResult(
        requested = requested, submitted = submitted, succeeded = succeeded,
        failed = failed, noteIds = emptyList(), noteIdsAvailable = false,
        errors = errors, persisted = persisted, refreshNotified = refreshNotified
    )

    @Test
    fun `shouldMarkBatchToolError true when failed greater than 0`() {
        assertTrue(shouldMarkBatchToolError(batchResult(succeeded = 1, failed = 1)))
    }

    @Test
    fun `shouldMarkBatchToolError true when errors non-empty even if failed is 0`() {
        // 安全网：存在错误即判错（真实流程中 failed 通常已 >0，此处作为兜底）
        val errs = listOf(BatchError(0, AnkiErrors.FIELD_NOT_FOUND, "x"))
        assertTrue(shouldMarkBatchToolError(batchResult(succeeded = 1, failed = 0, errors = errs)))
    }

    @Test
    fun `shouldMarkBatchToolError true when succeeded but not persisted`() {
        assertTrue(shouldMarkBatchToolError(batchResult(succeeded = 2, failed = 0, persisted = false)))
    }

    @Test
    fun `shouldMarkBatchToolError false when all success and persisted`() {
        assertFalse(shouldMarkBatchToolError(batchResult(succeeded = 2, failed = 0, persisted = true)))
    }

    @Test
    fun `shouldMarkBatchToolError false when only refreshNotified is false`() {
        // 刷新是 best-effort，不应单独导致工具调用失败
        assertFalse(
            shouldMarkBatchToolError(
                batchResult(succeeded = 2, failed = 0, persisted = true, refreshNotified = false)
            )
        )
    }
}
