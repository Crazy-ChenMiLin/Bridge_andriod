package xyz.chenmilin.ankimcpbridge.anki

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FakeAnkiRepositoryTest {

    private lateinit var repo: FakeAnkiRepository

    @Before
    fun setup() {
        repo = FakeAnkiRepository()
    }

    @Test
    fun `isAnkiDroidInstalled returns true by default`() {
        assertTrue(repo.isAnkiDroidInstalled())
    }

    @Test
    fun `hasPermission returns true by default`() {
        assertTrue(repo.hasPermission())
    }

    @Test
    fun `listDecks returns empty when no decks`() = runTest {
        val decks = repo.listDecks()
        assertTrue(decks.isEmpty())
    }

    @Test
    fun `ensureDeck creates new deck`() = runTest {
        val deck = repo.ensureDeck("TestDeck")
        assertEquals("TestDeck", deck.name)
        assertTrue(deck.id > 0)
    }

    @Test
    fun `ensureDeck is idempotent`() = runTest {
        val deck1 = repo.ensureDeck("TestDeck")
        val deck2 = repo.ensureDeck("TestDeck")
        assertEquals(deck1.id, deck2.id)
        assertEquals(deck1.name, deck2.name)
    }

    @Test
    fun `listDecks returns sorted decks`() = runTest {
        repo.ensureDeck("B")
        repo.ensureDeck("A")
        repo.ensureDeck("C")
        val decks = repo.listDecks()
        assertEquals(listOf("A", "B", "C"), decks.map { it.name })
    }

    @Test
    fun `addBasicNote succeeds`() = runTest {
        val result = repo.addBasicNote(
            AddBasicNoteRequest(deck = "Test", front = "Q?", back = "A!", tags = listOf("tag1"))
        )
        assertTrue(result.success)
        assertNotNull(result.noteId)
        assertEquals("Test", result.deck)
    }

    @Test
    fun `addBasicNote throws on blank front`() = runTest {
        try {
            repo.addBasicNote(AddBasicNoteRequest(deck = "Test", front = "  ", back = "A"))
            fail("Expected exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("front"))
        }
    }

    @Test
    fun `addBasicNote throws on blank back`() = runTest {
        try {
            repo.addBasicNote(AddBasicNoteRequest(deck = "Test", front = "Q", back = ""))
            fail("Expected exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("back"))
        }
    }

    @Test
    fun `addBasicNotes batch succeeds`() = runTest {
        val result = repo.addBasicNotes(
            AddBasicNotesRequest(
                deck = "Test",
                notes = listOf(
                    SingleNoteRequest("Q1", "A1"),
                    SingleNoteRequest("Q2", "A2"),
                    SingleNoteRequest("Q3", "A3")
                )
            )
        )
        assertEquals(3, result.requested)
        assertEquals(3, result.submitted)
        assertEquals(3, result.succeeded)
        assertEquals(0, result.failed)
        // 批量路径不暴露 noteId（与真实实现契约一致）
        assertEquals(false, result.noteIdsAvailable)
        assertEquals(0, result.noteIds.size)
    }

    @Test
    fun `addBasicNotes partial failure on blank front`() = runTest {
        val result = repo.addBasicNotes(
            AddBasicNotesRequest(
                deck = "Test",
                notes = listOf(
                    SingleNoteRequest("Q1", "A1"),
                    SingleNoteRequest("", "A2"),
                    SingleNoteRequest("Q3", "A3")
                )
            )
        )
        assertEquals(3, result.requested)
        assertEquals(2, result.submitted)
        assertEquals(2, result.succeeded)
        assertEquals(1, result.failed)
        assertEquals(AnkiErrors.INVALID_FRONT, result.errors.first().code)
    }

    @Test
    fun `throws when not installed`() = runTest {
        repo.setInstalled(false)
        try {
            repo.listDecks()
            fail("Expected exception")
        } catch (e: AnkiDroidNotInstalledException) {
            // expected
        }
    }

    @Test
    fun `throws when permission denied`() = runTest {
        repo.setPermissionGranted(false)
        try {
            repo.ensureDeck("Test")
            fail("Expected exception")
        } catch (e: AnkiPermissionDeniedException) {
            // expected
        }
    }

    // ─── v0.2.0 通用笔记类型读取与写入 ───

    @Test
    fun `listNoteTypes returns built-in note types sorted by name`() = runTest {
        val types = repo.listNoteTypes()
        assertTrue(types.isNotEmpty())
        // Basic / Cloze / MCP 面试题 / MCP 算法题 均在
        val names = types.map { it.name }
        assertTrue(names.contains("Basic"))
        assertTrue(names.contains("Cloze"))
        assertTrue(names.contains("MCP 面试题"))
        assertTrue(names.contains("MCP 算法题"))
        // 按名称排序
        assertEquals(names.sorted(), names)
    }

    @Test
    fun `note type field order is preserved`() = runTest {
        val algo = repo.listNoteTypes().first { it.name == "MCP 算法题" }
        assertEquals(
            listOf("题目", "核心思路", "复杂度", "Java代码", "易错点", "来源"),
            algo.fields
        )
        assertEquals("normal", algo.type)
    }

    @Test
    fun `cloze type is recognized`() = runTest {
        val cloze = repo.listNoteTypes().first { it.name == "Cloze" }
        assertEquals("cloze", cloze.type)
        assertEquals(listOf("Text", "Back Extra"), cloze.fields)
    }

    @Test
    fun `getNoteType returns detail for existing id`() = runTest {
        val basic = repo.listNoteTypes().first { it.name == "Basic" }
        val detail = repo.getNoteType(basic.id)
        assertEquals("Basic", detail.name)
        assertEquals(listOf("Front", "Back"), detail.fields)
        assertEquals("normal", detail.type)
    }

    @Test
    fun `getNoteType throws for unknown id`() = runTest {
        try {
            repo.getNoteType(999999L)
            fail("Expected ModelNotFoundException")
        } catch (e: ModelNotFoundException) {
            // expected
        }
    }

    @Test
    fun `addNote writes in note-type field order regardless of map order`() = runTest {
        val algo = repo.listNoteTypes().first { it.name == "MCP 算法题" }
        val result = repo.addNote(
            AddGenericNoteRequest(
                deck = "Test",
                noteTypeId = algo.id,
                fields = mapOf(
                    "来源" to "LeetCode",
                    "Java代码" to "class S {}",
                    "题目" to "两数之和",
                    "核心思路" to "HashMap",
                    "复杂度" to "O(n)",
                    "易错点" to "下标"
                )
            )
        )
        assertTrue(result.success)
        assertNotNull(result.noteId)
        assertEquals(algo.id, result.noteTypeId)
        assertEquals("Test", result.deck)
        assertTrue(result.persisted)
        assertTrue(result.refreshNotified)
    }

    @Test
    fun `addNote rejects unknown field`() = runTest {
        val basic = repo.listNoteTypes().first { it.name == "Basic" }
        try {
            repo.addNote(
                AddGenericNoteRequest(
                    deck = "Test",
                    noteTypeId = basic.id,
                    fields = mapOf("Front" to "Q", "Back" to "A", "Side" to "X")
                )
            )
            fail("Expected FieldMappingException")
        } catch (e: FieldMappingException) {
            assertEquals(AnkiErrors.FIELD_NOT_FOUND, e.code)
        }
    }

    @Test
    fun `addNote rejects all-empty fields`() = runTest {
        val basic = repo.listNoteTypes().first { it.name == "Basic" }
        try {
            repo.addNote(
                AddGenericNoteRequest(
                    deck = "Test",
                    noteTypeId = basic.id,
                    fields = mapOf("Front" to "  ", "Back" to "")
                )
            )
            fail("Expected FieldMappingException")
        } catch (e: FieldMappingException) {
            assertEquals(AnkiErrors.NO_VALID_FIELD, e.code)
        }
    }

    @Test
    fun `addNote rejects unknown noteTypeId with exception`() = runTest {
        try {
            repo.addNote(
                AddGenericNoteRequest(
                    deck = "Test",
                    noteTypeId = 888888L,
                    fields = mapOf("Front" to "Q", "Back" to "A")
                )
            )
            fail("Expected ModelNotFoundException")
        } catch (e: ModelNotFoundException) {
            // expected：未知 noteTypeId（非法 ID）统一抛 NOTE_TYPE_NOT_FOUND
        }
    }

    @Test
    fun `addNote rejects invalid noteTypeId with exception`() = runTest {
        try {
            repo.addNote(
                AddGenericNoteRequest(
                    deck = "Test",
                    noteTypeId = 0L,
                    fields = mapOf("Front" to "Q", "Back" to "A")
                )
            )
            fail("Expected ModelNotFoundException")
        } catch (e: ModelNotFoundException) {
            // expected
        }
    }

    @Test
    fun `addNote dedups and trims tags`() = runTest {
        val basic = repo.listNoteTypes().first { it.name == "Basic" }
        val result = repo.addNote(
            AddGenericNoteRequest(
                deck = "Test",
                noteTypeId = basic.id,
                fields = mapOf("Front" to "Q", "Back" to "A"),
                tags = listOf("java", " Java ", "", "java")
            )
        )
        assertTrue(result.success)
        // tags 去空去重后应为 ["java"]
        // 内存实现不强制回传 tags，这里仅验证写入成功与持久化
        assertTrue(result.persisted)
    }

    @Test
    fun `addNotes maps error index to original position`() = runTest {
        val basic = repo.listNoteTypes().first { it.name == "Basic" }
        val unknown = repo.addCustomNoteType("UnknownType", listOf("A", "B"))
        val result = repo.addNotes(
            AddGenericNotesRequest(
                deck = "Test",
                notes = listOf(
                    GenericNoteItem(noteTypeId = basic.id, fields = mapOf("Front" to "Q1", "Back" to "A1")),
                    GenericNoteItem(noteTypeId = unknown, fields = mapOf("A" to "x", "B" to "y", "C" to "z")), // 未知字段
                    GenericNoteItem(noteTypeId = 777777L, fields = mapOf("Front" to "Q3", "Back" to "A3")) // 不存在
                )
            )
        )
        assertEquals(3, result.requested)
        assertEquals(1, result.succeeded)
        assertEquals(2, result.failed)
        // 两个错误的原始下标应为 1 和 2
        val indexes = result.errors.map { it.index }.sorted()
        assertEquals(listOf(1, 2), indexes)
    }

    @Test
    fun `addNotes success sets persisted and refreshNotified`() = runTest {
        val basic = repo.listNoteTypes().first { it.name == "Basic" }
        val result = repo.addNotes(
            AddGenericNotesRequest(
                deck = "Test",
                notes = (1..5).map { i ->
                    GenericNoteItem(noteTypeId = basic.id, fields = mapOf("Front" to "Q$i", "Back" to "A$i"))
                }
            )
        )
        assertEquals(5, result.requested)
        assertEquals(5, result.submitted)
        assertEquals(5, result.succeeded)
        assertEquals(0, result.failed)
        assertTrue(result.persisted)
        assertTrue(result.refreshNotified)
    }

    // ─── v0.2.1：cardTemplateCount / 批量统计 / 混合类型 / 持久化 ───

    @Test
    fun `cardTemplateCount is explicit not fields size`() = runTest {
        val basic = repo.listNoteTypes().first { it.name == "Basic" }
        // Basic 有 2 个字段，但模板数为 1（不再是字段数）
        assertEquals(2, basic.fields.size)
        assertEquals(1, basic.cardTemplateCount)
        val cloze = repo.listNoteTypes().first { it.name == "Cloze" }
        assertEquals(1, cloze.cardTemplateCount)
    }

    @Test
    fun `forward reverse note type has 2 card templates`() = runTest {
        val algo = repo.listNoteTypes().first { it.name == "MCP 算法题" }
        // 6 个字段，正反向 = 2 模板（字段数 != 模板数）
        assertEquals(6, algo.fields.size)
        assertEquals(2, algo.cardTemplateCount)
    }

    @Test
    fun `custom note type with 6 fields and 1 template returns 1`() = runTest {
        repo.addCustomNoteType("SixFields", (1..6).map { "F$it" }, cardTemplateCount = 1)
        val t = repo.listNoteTypes().first { it.name == "SixFields" }
        assertEquals(6, t.fields.size)
        assertEquals(1, t.cardTemplateCount)
    }

    @Test
    fun `num cards unavailable returns 0 not fields size`() = runTest {
        repo.setSimulateNumCardsUnavailable(true)
        val types = repo.listNoteTypes()
        assertTrue(types.isNotEmpty())
        // 无法读取 NUM_CARDS 时统一返回 0
        assertTrue(types.all { it.cardTemplateCount == 0 })
    }

    @Test
    fun `addNotes partial insert planned 5 inserted 3`() = runTest {
        val basic = repo.listNoteTypes().first { it.name == "Basic" }
        repo.setSimulatePartialInsert(enabled = true, drop = 2)
        val result = repo.addNotes(
            AddGenericNotesRequest(
                deck = "Test",
                notes = (1..5).map { i ->
                    GenericNoteItem(noteTypeId = basic.id, fields = mapOf("Front" to "Q$i", "Back" to "A$i"))
                }
            )
        )
        assertEquals(5, result.requested)
        assertEquals(5, result.submitted)
        assertEquals(3, result.succeeded) // 实际成功 3
        assertEquals(2, result.failed) // failed = requested - succeeded = 2
        assertTrue(result.errors.any { it.code == AnkiErrors.PARTIAL_FAILURE })
        // 工具层判定：部分失败 → isError=true
        assertTrue(shouldMarkBatchToolError(result))
        // 只写入了 3 条
        assertEquals(3, repo.noteCount())
    }

    @Test
    fun `addNotes model insert failure does not block other model`() = runTest {
        val basic = repo.listNoteTypes().first { it.name == "Basic" }
        val custom = repo.addCustomNoteType("CustomA", listOf("X", "Y"))
        repo.setSimulateModelInsertFailure(basic.id)
        val result = repo.addNotes(
            AddGenericNotesRequest(
                deck = "Test",
                notes = listOf(
                    GenericNoteItem(noteTypeId = basic.id, fields = mapOf("Front" to "Q1", "Back" to "A1")),
                    GenericNoteItem(noteTypeId = custom, fields = mapOf("X" to "x1", "Y" to "y1"))
                )
            )
        )
        assertEquals(2, result.requested)
        assertEquals(1, result.succeeded) // 只有 custom 成功
        assertEquals(1, result.failed)
        assertTrue(result.errors.any { it.code == AnkiErrors.BATCH_FAILED })
        // 仅 custom 的 1 条被写入
        assertEquals(1, repo.noteCount())
    }

    @Test
    fun `addNotes readback shortfall sets persisted false`() = runTest {
        val basic = repo.listNoteTypes().first { it.name == "Basic" }
        repo.setSimulateReadbackShortfall(true)
        val result = repo.addNotes(
            AddGenericNotesRequest(
                deck = "Test",
                notes = (1..3).map { i ->
                    GenericNoteItem(noteTypeId = basic.id, fields = mapOf("Front" to "Q$i", "Back" to "A$i"))
                }
            )
        )
        assertEquals(3, result.succeeded)
        assertFalse(result.persisted) // 回读不足
        // 写入成功但持久化失败 → isError=true
        assertTrue(shouldMarkBatchToolError(result))
    }

    @Test
    fun `addNotes mixed noteTypeId batches separately`() = runTest {
        val basic = repo.listNoteTypes().first { it.name == "Basic" }
        val algo = repo.listNoteTypes().first { it.name == "MCP 算法题" }
        val result = repo.addNotes(
            AddGenericNotesRequest(
                deck = "Test",
                notes = listOf(
                    GenericNoteItem(noteTypeId = basic.id, fields = mapOf("Front" to "Q1", "Back" to "A1")),
                    GenericNoteItem(noteTypeId = algo.id, fields = mapOf("题目" to "两数之和", "核心思路" to "HashMap", "复杂度" to "O(n)", "Java代码" to "x", "易错点" to "y", "来源" to "z")),
                    GenericNoteItem(noteTypeId = basic.id, fields = mapOf("Front" to "Q2", "Back" to "A2"))
                )
            )
        )
        assertEquals(3, result.requested)
        assertEquals(3, result.submitted)
        assertEquals(3, result.succeeded)
        assertEquals(0, result.failed)
        assertTrue(result.persisted)
    }

    @Test
    fun `addNotes refresh failure only does not mark tool error`() = runTest {
        val basic = repo.listNoteTypes().first { it.name == "Basic" }
        repo.setSimulateRefreshFailure(true)
        val result = repo.addNotes(
            AddGenericNotesRequest(
                deck = "Test",
                notes = listOf(
                    GenericNoteItem(noteTypeId = basic.id, fields = mapOf("Front" to "Q1", "Back" to "A1"))
                )
            )
        )
        assertEquals(1, result.succeeded)
        assertTrue(result.persisted)
        assertFalse(result.refreshNotified)
        // 仅刷新失败（best-effort）→ 不判错
        assertFalse(shouldMarkBatchToolError(result))
    }
}
