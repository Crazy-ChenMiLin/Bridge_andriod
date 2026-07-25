package xyz.chenmilin.ankimcpbridge.anki

/**
 * 测试用 AnkiRepository 实现。
 * 使用内存数据结构模拟 AnkiDroid API 行为。
 */
class FakeAnkiRepository : AnkiRepository {

    private val decks = mutableListOf<AnkiDeck>()
    private val notes = mutableListOf<FakeNote>()
    private val noteTypes = mutableListOf<FakeNoteType>()
    private var nextDeckId: Long = 1
    private var nextNoteId: Long = 1
    private var nextNoteTypeId: Long = 1
    private var installed = true
    private var permissionGranted = true

    fun setInstalled(value: Boolean) { installed = value }
    fun setPermissionGranted(value: Boolean) { permissionGranted = value }

    init {
        // 内置测试用笔记类型：Basic / Cloze / MCP 面试题 / MCP 算法题
        addNoteType("Basic", listOf("Front", "Back"), "normal")
        addNoteType("Cloze", listOf("Text", "Back Extra"), "cloze")
        addNoteType("MCP 面试题", listOf("问题", "简答", "详细回答", "案例", "追问", "来源"), "normal")
        addNoteType("MCP 算法题", listOf("题目", "核心思路", "复杂度", "Java代码", "易错点", "来源"), "normal")
    }

    private fun addNoteType(name: String, fields: List<String>, type: String): FakeNoteType {
        val nt = FakeNoteType(id = nextNoteTypeId++, name = name, fields = fields, type = type)
        noteTypes.add(nt)
        return nt
    }

    override fun isAnkiDroidInstalled(): Boolean = installed

    override fun hasPermission(): Boolean = permissionGranted

    override suspend fun listDecks(): List<AnkiDeck> {
        if (!installed) throw AnkiDroidNotInstalledException()
        if (!permissionGranted) throw AnkiPermissionDeniedException()
        return decks.sortedBy { it.name }
    }

    override suspend fun ensureDeck(name: String): AnkiDeck {
        if (!installed) throw AnkiDroidNotInstalledException()
        if (!permissionGranted) throw AnkiPermissionDeniedException()

        val trimmed = name.trim()
        val existing = decks.find { it.name.equals(trimmed, ignoreCase = true) }
        if (existing != null) return existing

        val deck = AnkiDeck(id = nextDeckId++, name = trimmed)
        decks.add(deck)
        return deck
    }

    override suspend fun addBasicNote(request: AddBasicNoteRequest): AddNoteResult {
        if (!installed) throw AnkiDroidNotInstalledException()
        if (!permissionGranted) throw AnkiPermissionDeniedException()
        if (request.front.isBlank()) throw IllegalArgumentException("front must not be blank")
        if (request.back.isBlank()) throw IllegalArgumentException("back must not be blank")

        val deck = ensureDeck(request.deck)
        val noteId = nextNoteId++
        notes.add(
            FakeNote(
                id = noteId,
                deckId = deck.id,
                noteTypeId = noteTypes.first { it.name == "Basic" }.id,
                fields = mapOf("Front" to request.front, "Back" to request.back),
                tags = request.tags
            )
        )
        return AddNoteResult(success = true, noteId = noteId, deck = deck.name)
    }

    override suspend fun addBasicNotes(request: AddBasicNotesRequest): BatchAddResult {
        if (!installed) throw AnkiDroidNotInstalledException()
        if (!permissionGranted) throw AnkiPermissionDeniedException()

        val deck = ensureDeck(request.deck)
        val succeededIds = mutableListOf<Long>()
        val errors = mutableListOf<BatchError>()

        for ((index, note) in request.notes.withIndex()) {
            if (note.front.isBlank()) {
                errors.add(BatchError(index, AnkiErrors.INVALID_FRONT, "front must not be blank"))
                continue
            }
            if (note.back.isBlank()) {
                errors.add(BatchError(index, AnkiErrors.INVALID_BACK, "back must not be blank"))
                continue
            }
            val noteId = nextNoteId++
            notes.add(
                FakeNote(
                    id = noteId, deckId = deck.id,
                    noteTypeId = noteTypes.first { it.name == "Basic" }.id,
                    fields = mapOf("Front" to note.front, "Back" to note.back), tags = note.tags
                )
            )
            succeededIds.add(noteId)
        }

        // 与真实实现保持一致的契约：批量路径不暴露 noteId（noteIdsAvailable=false）。
        return BatchAddResult(
            requested = request.notes.size,
            submitted = succeededIds.size,
            succeeded = succeededIds.size,
            failed = errors.size,
            noteIds = emptyList(),
            noteIdsAvailable = false,
            errors = errors
        )
    }

    // ── v0.2.0 通用笔记类型读取与写入 ──

    override suspend fun listNoteTypes(): List<AnkiNoteTypeSummary> {
        if (!installed) throw AnkiDroidNotInstalledException()
        if (!permissionGranted) throw AnkiPermissionDeniedException()
        return noteTypes.map { nt ->
            AnkiNoteTypeSummary(
                id = nt.id, name = nt.name, fields = nt.fields, type = nt.type,
                cardTemplateCount = nt.fields.size
            )
        }.sortedBy { it.name }
    }

    override suspend fun getNoteType(noteTypeId: Long): AnkiNoteTypeDetail {
        if (!installed) throw AnkiDroidNotInstalledException()
        if (!permissionGranted) throw AnkiPermissionDeniedException()
        if (noteTypeId <= 0) throw IllegalArgumentException("noteTypeId 非法: $noteTypeId")
        val nt = noteTypes.firstOrNull { it.id == noteTypeId }
            ?: throw ModelNotFoundException("笔记类型不存在: $noteTypeId")
        return AnkiNoteTypeDetail(
            id = nt.id, name = nt.name, fields = nt.fields, type = nt.type,
            css = null, templates = emptyList()
        )
    }

    override suspend fun addNote(request: AddGenericNoteRequest): AddGenericNoteResult {
        if (!installed) throw AnkiDroidNotInstalledException()
        if (!permissionGranted) throw AnkiPermissionDeniedException()

        val nt = noteTypes.firstOrNull { it.id == request.noteTypeId }
            ?: return AddGenericNoteResult(
                success = false, noteId = null, deck = request.deck.trim(),
                noteTypeId = request.noteTypeId, persisted = false, refreshNotified = false
            )

        // 复用真实实现的字段映射规则（顺序映射、未知字段拒绝、至少一非空）。
        // 字段映射失败直接抛出，与真实实现一致，由工具层转为带错误码的业务错误。
        val fieldValues = mapNoteFields(nt.fields, request.fields).toList()

        val deck = ensureDeck(request.deck)
        val noteId = nextNoteId++
        notes.add(
            FakeNote(
                id = noteId, deckId = deck.id, noteTypeId = nt.id,
                fields = nt.fields.zip(fieldValues).toMap(), tags = request.tags
            )
        )
        // 内存实现：写入后即可读回，persisted=true；刷新通知在真实实现中发送，此处标记为已通知。
        return AddGenericNoteResult(
            success = true, noteId = noteId, deck = deck.name,
            noteTypeId = nt.id, persisted = true, refreshNotified = true
        )
    }

    override suspend fun addNotes(request: AddGenericNotesRequest): BatchAddGenericResult {
        if (!installed) throw AnkiDroidNotInstalledException()
        if (!permissionGranted) throw AnkiPermissionDeniedException()

        val deck = ensureDeck(request.deck)
        val errors = mutableListOf<BatchError>()
        var succeeded = 0

        request.notes.forEachIndexed { index, item ->
            val nt = noteTypes.firstOrNull { it.id == item.noteTypeId }
            if (nt == null) {
                errors.add(BatchError(index, AnkiErrors.NOTE_TYPE_NOT_FOUND, "第 ${index + 1} 项笔记类型不存在"))
                return@forEachIndexed
            }
            try {
                mapNoteFields(nt.fields, item.fields)
            } catch (e: FieldMappingException) {
                errors.add(BatchError(index, e.code, "第 ${index + 1} 项: ${e.message}"))
                return@forEachIndexed
            }
            val noteId = nextNoteId++
            notes.add(
                FakeNote(
                    id = noteId, deckId = deck.id, noteTypeId = nt.id,
                    fields = nt.fields.zip(
                        mapNoteFields(nt.fields, item.fields).toList()
                    ).toMap(),
                    tags = item.tags
                )
            )
            succeeded++
        }

        return BatchAddGenericResult(
            requested = request.notes.size,
            submitted = succeeded,
            succeeded = succeeded,
            failed = errors.size,
            noteIds = emptyList(),
            noteIdsAvailable = false,
            errors = errors,
            persisted = errors.isEmpty(),
            refreshNotified = true
        )
    }

    // ── 测试辅助 ──

    /** 注入一个自定义笔记类型（测试用）。返回其 ID。 */
    fun addCustomNoteType(name: String, fields: List<String>, type: String = "normal"): Long {
        return addNoteType(name, fields, type).id
    }

    /** 当前内存中的笔记总数（用于断言）。 */
    fun noteCount(): Int = notes.size

    data class FakeNote(
        val id: Long,
        val deckId: Long,
        val noteTypeId: Long,
        val fields: Map<String, String>,
        val tags: List<String>
    )

    data class FakeNoteType(
        val id: Long,
        val name: String,
        val fields: List<String>,
        val type: String
    )
}
