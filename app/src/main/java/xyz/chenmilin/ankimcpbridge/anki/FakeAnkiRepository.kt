package xyz.chenmilin.ankimcpbridge.anki

/**
 * 测试用 AnkiRepository 实现。
 * 使用内存数据结构模拟 AnkiDroid API 行为。
 *
 * v0.2.1：统一真实实现的批量语义——
 * - 批量失败数 [BatchAddGenericResult.failed] = requested - succeeded；
 * - 按 noteTypeId 分组、逐模型“插入”，移组数量 = 实际插入数量；
 * - [cardTemplateCount] 为显式字段，不再等于字段数；
 * - 未知 noteTypeId 抛 [ModelNotFoundException]，与真实实现一致。
 *
 * 此外提供一组 `setSimulateXxx` 开关，用于模拟真实实现中难以在普通单元测试里触发的
 * 边界（部分插入、整组插入失败、回读不足、刷新失败），从而让工具层与汇总逻辑可被测到。
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

    // ── 边界模拟开关（测试用） ──
    private var simulatePartialInsert: Boolean = false
    private var simulatePartialInsertDrop: Int = 1
    private var simulateModelInsertFailures: Set<Long> = emptySet()
    private var simulateReadbackShortfall: Boolean = false
    private var simulateRefreshFailure: Boolean = false
    private var simulateNumCardsUnavailable: Boolean = false

    fun setInstalled(value: Boolean) { installed = value }
    fun setPermissionGranted(value: Boolean) { permissionGranted = value }

    /** 模拟“某模型批量插入只成功部分”（drop=丢弃的条数，默认 1）。 */
    fun setSimulatePartialInsert(enabled: Boolean, drop: Int = 1) {
        simulatePartialInsert = enabled
        simulatePartialInsertDrop = drop
    }

    /** 模拟“指定 noteTypeId 的整组批量插入失败”（insert 返回负值）。 */
    fun setSimulateModelInsertFailure(vararg noteTypeIds: Long) {
        simulateModelInsertFailures = noteTypeIds.toSet()
    }

    /** 模拟“批量回读验证不足”（persisted=false）。 */
    fun setSimulateReadbackShortfall(enabled: Boolean) { simulateReadbackShortfall = enabled }

    /** 模拟“本地刷新通知失败”（refreshNotified=false）。 */
    fun setSimulateRefreshFailure(enabled: Boolean) { simulateRefreshFailure = enabled }

    /** 模拟“无法读取 NUM_CARDS”（listNoteTypes 全部返回 cardTemplateCount=0）。 */
    fun setSimulateNumCardsUnavailable(enabled: Boolean) { simulateNumCardsUnavailable = enabled }

    init {
        // 内置测试用笔记类型：Basic / Cloze / MCP 面试题 / MCP 算法题
        // Basic=1 模板；Cloze=1 模板；面试题/算法题为“正反向”类型 = 2 模板。
        addNoteType("Basic", listOf("Front", "Back"), "normal", cardTemplateCount = 1)
        addNoteType("Cloze", listOf("Text", "Back Extra"), "cloze", cardTemplateCount = 1)
        addNoteType("MCP 面试题", listOf("问题", "简答", "详细回答", "案例", "追问", "来源"), "normal", cardTemplateCount = 2)
        addNoteType("MCP 算法题", listOf("题目", "核心思路", "复杂度", "Java代码", "易错点", "来源"), "normal", cardTemplateCount = 2)
    }

    private fun addNoteType(name: String, fields: List<String>, type: String, cardTemplateCount: Int = 1): FakeNoteType {
        val nt = FakeNoteType(id = nextNoteTypeId++, name = name, fields = fields, type = type, cardTemplateCount = cardTemplateCount)
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
        if (trimmed.isEmpty()) throw IllegalArgumentException("牌组名称不能为空")
        val existing = decks.find { it.name.equals(trimmed, ignoreCase = true) }
        if (existing != null) return existing.copy(created = false)

        val deck = AnkiDeck(id = nextDeckId++, name = trimmed, created = true)
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
            failed = calculateBatchFailed(request.notes.size, succeededIds.size),
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
                // 无法读取 NUM_CARDS 时返回 0（模拟真实实现 else 分支），绝不等于字段数。
                cardTemplateCount = if (simulateNumCardsUnavailable) 0 else nt.cardTemplateCount
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
        if (request.noteTypeId <= 0) {
            throw ModelNotFoundException("笔记类型不存在或 noteTypeId 非法: ${request.noteTypeId}")
        }

        val nt = noteTypes.firstOrNull { it.id == request.noteTypeId }
            ?: throw ModelNotFoundException("笔记类型不存在或无法读取字段: ${request.noteTypeId}")

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
            noteTypeId = nt.id, persisted = true, refreshNotified = !simulateRefreshFailure,
            deckId = deck.id, deckCreated = deck.created
        )
    }

    override suspend fun addNotes(request: AddGenericNotesRequest): BatchAddGenericResult {
        if (!installed) throw AnkiDroidNotInstalledException()
        if (!permissionGranted) throw AnkiPermissionDeniedException()

        val deck = ensureDeck(request.deck)
        val requested = request.notes.size
        val errors = mutableListOf<BatchError>()
        val validPlans = mutableListOf<GenericPlan>()

        // 1) 预校验（与真实实现一致）：未知类型 / 字段映射失败 → 记录原始下标，继续处理其他项
        request.notes.forEachIndexed { index, item ->
            val nt = noteTypes.firstOrNull { it.id == item.noteTypeId }
            if (nt == null) {
                errors.add(BatchError(index, AnkiErrors.NOTE_TYPE_NOT_FOUND, "第 ${index + 1} 项笔记类型不存在"))
                return@forEachIndexed
            }
            val mapped = try {
                mapNoteFields(nt.fields, item.fields).toList()
            } catch (e: FieldMappingException) {
                errors.add(BatchError(index, e.code, "第 ${index + 1} 项: ${e.message}"))
                return@forEachIndexed
            }
            val tags = item.tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            validPlans.add(GenericPlan(index, item.noteTypeId, mapped, tags))
        }

        val submitted = validPlans.size

        // 2) 按 noteTypeId 分组，逐模型插入（真实实现为逐模型 bulkInsert）
        val grouped = validPlans.groupBy { it.noteTypeId }
        val groups: Map<Long, ModelBatchPlan<GenericPlan>> = grouped.mapValues { (noteTypeId, plans) ->
            ModelBatchPlan(
                noteTypeId = noteTypeId,
                values = plans,
                originalIndexes = plans.map { it.index }
            )
        }

        val summary = executeBatchInsert(groups) { noteTypeId, plans ->
            if (simulateModelInsertFailures.contains(noteTypeId)) {
                // 整组插入失败（对应真实 bulkInsert 返回负值）
                -1
            } else {
                val drop = if (simulatePartialInsert) simulatePartialInsertDrop else 0
                (plans.size - drop).coerceAtLeast(0)
            }
        }

        // 3) 按各模型“实际插入数量”写入内存（只写 inserted 条，不写计划全部）
        for ((noteTypeId, inserted) in summary.insertedByModel) {
            if (inserted <= 0) continue
            val plans = grouped[noteTypeId] ?: continue
            val nt = noteTypes.first { it.id == noteTypeId }
            for (i in 0 until inserted) {
                val p = plans[i]
                val noteId = nextNoteId++
                notes.add(
                    FakeNote(
                        id = noteId, deckId = deck.id, noteTypeId = noteTypeId,
                        fields = nt.fields.zip(p.fields).toMap(), tags = p.tags
                    )
                )
            }
        }

        // 4) 回读验证持久化（best-effort）：每个模型内存中的笔记数 >= 实际插入数
        val readCounts = summary.insertedByModel.mapValues { (modelId, _) ->
            notes.count { it.noteTypeId == modelId }
        }
        val persisted = if (simulateReadbackShortfall) {
            false
        } else {
            checkBatchPersisted(summary.insertedByModel, readCounts)
        }

        val succeeded = summary.totalInserted
        val refreshNotified = !simulateRefreshFailure

        return BatchAddGenericResult(
            requested = requested,
            submitted = submitted,
            succeeded = succeeded,
            failed = calculateBatchFailed(requested, succeeded),
            noteIds = emptyList(),
            noteIdsAvailable = false,
            errors = errors + summary.insertErrors,
            persisted = persisted,
            refreshNotified = refreshNotified,
            deckId = deck.id,
            deckCreated = deck.created
        )
    }

    // ── 测试辅助 ──

    /** 注入一个自定义笔记类型（测试用）。返回其 ID，默认 1 个模板。 */
    fun addCustomNoteType(name: String, fields: List<String>, type: String = "normal", cardTemplateCount: Int = 1): Long {
        return addNoteType(name, fields, type, cardTemplateCount).id
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
        val type: String,
        val cardTemplateCount: Int
    )
}
