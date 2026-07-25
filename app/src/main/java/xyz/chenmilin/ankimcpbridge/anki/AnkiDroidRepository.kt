package xyz.chenmilin.ankimcpbridge.anki

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import com.ichi2.anki.FlashCardsContract
import xyz.chenmilin.ankimcpbridge.logging.AppLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 基于 AnkiDroid ContentProvider API（官方 [FlashCardsContract]）的实现。
 *
 * 等价复刻官方 `com.ichi2.anki.api.AddContentApi` 的关键能力：
 * - [deckList] / [addNewDeck]
 * - [modelList] / [getFieldList] / [addNewBasicModel]
 * - [addNote] / [addNotes]（批量）
 *
 * 设计要点（v0.1.1 修复）：
 * 1. **权限检查**：[hasPermission] 使用正式的 [Context.checkPermission]，校验本应用是否持有
 *    AnkiDroid 的 `READ_WRITE_DATABASE` 权限（该权限需在 AndroidManifest 中声明）。
 * 2. **批量插入**：[addBasicNotes] 通过单次 [ContentResolver.bulkInsert] 完成（等价官方
 *    `AddContentApi.addNotes(modelId, deckId, fieldsList, tagsList)`），不再循环调用
 *    `contentResolver.insert`。由于 [bulkInsert] 只返回写入行数、不返回单个 noteId，
 *    批量结果中 [BatchAddResult.noteIdsAvailable] 恒为 false。
 * 3. **初始化重试**：首次访问 ContentProvider 时 AnkiDroid 集合可能尚未就绪而抛异常。对此类
 *    “初始化”异常仅在 [withAnkiRetry] 中重试一次（延迟 [INIT_RETRY_DELAY_MS]）；权限不足、
 *    AnkiDroid 未安装等错误**不重试**，直接上抛为业务异常。
 * 4. **卡片移组**：笔记插入后卡片默认进入“默认牌组”。单条路径直接拿到 noteId 后移动卡片；
 *    批量路径在 [Mutex] 保护下，**按 noteTypeId 分组**逐模型 `bulkInsert`，并仅移动各模型
 *    **实际插入成功**的数量（按 `_id DESC`），确保 `deck` 参数语义成立且不会误移写入前的旧卡片。
 *    [failed] 统一为 `requested - succeeded`（覆盖全部预校验失败的情况）。
 *
 * 不依赖任何额外 JAR；Authority 固定为 `com.ichi2.anki.flashcards`。
 */
class AnkiDroidRepository(context: Context) : AnkiRepository {

    private val appContext = context.applicationContext
    /** 串行化所有“插入笔记 + 移动卡片”操作，保证批量路径的“最新 N 张”查询准确。 */
    private val addMutex = Mutex()
    private val logRepo = AppLogRepository.instance

    companion object {
        const val ANKIDROID_PACKAGE = "com.ichi2.anki"
        /** AnkiDroid 数据库读写权限（需要在 AndroidManifest 中声明）。 */
        const val READ_WRITE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        private const val BASIC_MODEL_NAME = "MCP Basic"
        private const val FIELD_SEPARATOR = "\u001f"
        /** 初始化异常重试一次时的延迟（毫秒）。 */
        private const val INIT_RETRY_DELAY_MS = 400L
    }

    override fun isAnkiDroidInstalled(): Boolean {
        return try {
            appContext.packageManager.getPackageInfo(ANKIDROID_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    override fun hasPermission(): Boolean {
        if (!isAnkiDroidInstalled()) return false
        return try {
            // 正式权限检查：本应用是否被授予 AnkiDroid 的 READ_WRITE_DATABASE 权限。
            val result = appContext.checkPermission(
                FlashCardsContract.READ_WRITE_PERMISSION,
                Process.myPid(),
                Process.myUid()
            )
            result == PackageManager.PERMISSION_GRANTED
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun listDecks(): List<AnkiDeck> = withAnkiRetry {
        doListDecks()
    }

    private suspend fun doListDecks(): List<AnkiDeck> = withContext(Dispatchers.IO) {
        ensureAvailable()
        readDecks().sortedBy { it.name }
    }

    override suspend fun ensureDeck(name: String): AnkiDeck = withAnkiRetry {
        doEnsureDeck(name)
    }

    private suspend fun doEnsureDeck(name: String): AnkiDeck = withContext(Dispatchers.IO) {
        ensureAvailable()
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) throw IllegalArgumentException("牌组名称不能为空")
        if (trimmedName.length > 200) throw IllegalArgumentException("牌组名称过长（最多200字符）")

        readDecks().firstOrNull { it.name.equals(trimmedName, ignoreCase = true) }?.let { deck ->
            return@withContext deck.copy(created = false)
        }
        val deckId = addNewDeck(trimmedName)
            ?: throw DeckOperationException("创建牌组失败: $trimmedName")
        AnkiDeck(id = deckId, name = trimmedName, created = true)
    }

    override suspend fun addBasicNote(request: AddBasicNoteRequest): AddNoteResult =
        withAnkiRetry { doAddBasicNote(request) }

    private suspend fun doAddBasicNote(request: AddBasicNoteRequest): AddNoteResult =
        withContext(Dispatchers.IO) {
            // 保留原有契约：front/back 为空直接抛异常（不进入通用映射）。
            if (request.front.isBlank()) throw IllegalArgumentException("front must not be blank")
            if (request.back.isBlank()) throw IllegalArgumentException("back must not be blank")

            // 复用通用写入链路（写入 / 卡片移组 / 回读验证 / 刷新通知），避免两套独立底层逻辑。
            logRepo.info("add_basic_note 请求参数摘要: deck=${request.deck.trim()}, noteCount=1")
            val modelId = AnkiModelResolver.resolveBasicModelId(appContext)
            val generic = doAddGenericNote(
                AddGenericNoteRequest(
                    deck = request.deck,
                    noteTypeId = modelId,
                    fields = mapOf(
                        AnkiModelResolver.FIELD_FRONT to request.front,
                        AnkiModelResolver.FIELD_BACK to request.back
                    ),
                    tags = request.tags
                )
            )
            AddNoteResult(success = generic.success, noteId = generic.noteId, deck = generic.deck)
        }

    override suspend fun addBasicNotes(request: AddBasicNotesRequest): BatchAddResult =
        withAnkiRetry { doAddBasicNotes(request) }

    // ───────────────────────────────────────────────────────────
    // v0.2.0：通用笔记类型读取与写入
    // ───────────────────────────────────────────────────────────

    override suspend fun listNoteTypes(): List<AnkiNoteTypeSummary> = withAnkiRetry {
        doListNoteTypes()
    }

    private suspend fun doListNoteTypes(): List<AnkiNoteTypeSummary> = withContext(Dispatchers.IO) {
        ensureAvailable()
        val result = mutableListOf<AnkiNoteTypeSummary>()
        appContext.contentResolver.query(
            FlashCardsContract.Model.CONTENT_URI, null, null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Model._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Model.NAME)
            val fldsIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Model.FIELD_NAMES)
            val numCardsIdx = cursor.getColumnIndex(FlashCardsContract.Model.NUM_CARDS)
            val typeIdx = cursor.getColumnIndex(FlashCardsContract.Model.TYPE)
            while (cursor.moveToNext()) {
                // 单条损坏不应拖垮整个列表：该条跳过并记录。
                try {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx) ?: continue
                    val fields = AnkiModelResolver.parseFieldNames(cursor.getString(fldsIdx) ?: "")
                        .toList()
                    val typeCode = if (typeIdx >= 0) cursor.getInt(typeIdx) else 0
                    val type = when (typeCode) {
                        1 -> "cloze"
                        0 -> "normal"
                        else -> "unknown"
                    }
                    // 无法读取 NUM_CARDS 时返回 0（表示“不可用/未知”），绝不用字段数代替模板数。
                    val cardTemplateCount =
                        if (numCardsIdx >= 0 && !cursor.isNull(numCardsIdx)) {
                            cursor.getInt(numCardsIdx)
                        } else {
                            0
                        }
                    result.add(AnkiNoteTypeSummary(id, name, fields, type, cardTemplateCount))
                } catch (e: Exception) {
                    logRepoWarn("读取笔记类型失败，已跳过: ${e.message}")
                }
            }
        }
        result.sortedBy { it.name }
    }

    override suspend fun getNoteType(noteTypeId: Long): AnkiNoteTypeDetail = withAnkiRetry {
        doGetNoteType(noteTypeId)
    }

    private suspend fun doGetNoteType(noteTypeId: Long): AnkiNoteTypeDetail = withContext(Dispatchers.IO) {
        ensureAvailable()
        if (noteTypeId <= 0) throw IllegalArgumentException("noteTypeId 非法: $noteTypeId")

        val uri = Uri.withAppendedPath(FlashCardsContract.Model.CONTENT_URI, noteTypeId.toString())
        var fields: List<String> = emptyList()
        var name = ""
        var type = "unknown"
        var css: String? = null
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw ModelNotFoundException("笔记类型不存在: $noteTypeId")
            val nameIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Model.NAME)
            val fldsIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Model.FIELD_NAMES)
            val typeIdx = cursor.getColumnIndex(FlashCardsContract.Model.TYPE)
            val cssIdx = cursor.getColumnIndex(FlashCardsContract.Model.CSS)
            name = cursor.getString(nameIdx) ?: ""
            fields = AnkiModelResolver.parseFieldNames(cursor.getString(fldsIdx) ?: "").toList()
            val typeCode = if (typeIdx >= 0) cursor.getInt(typeIdx) else 0
            type = when (typeCode) {
                1 -> "cloze"
                0 -> "normal"
                else -> "unknown"
            }
            // CSS 部分 AnkiDroid 版本/接口可能不返回，读不到返回 null（不绕过私有数据库）。
            css = if (cssIdx >= 0) cursor.getString(cssIdx) else null
        } ?: throw ModelNotFoundException("笔记类型不存在: $noteTypeId")

        // 卡片模板：通过 models/<id>/templates 读取；读不到时返回空列表（best-effort）。
        // 部分 AnkiDroid 版本/接口可能不支持该 URI，失败不应中断整体。
        val templates = readCardTemplates(noteTypeId)

        return@withContext AnkiNoteTypeDetail(
            id = noteTypeId,
            name = name,
            fields = fields,
            type = type,
            css = css,
            templates = templates
        )
    }

    /** 读取笔记类型的卡片模板（best-effort）。读不到/失败时返回空列表。 */
    private fun readCardTemplates(noteTypeId: Long): List<AnkiCardTemplate> {
        val templatesUri = Uri.withAppendedPath(
            Uri.withAppendedPath(FlashCardsContract.Model.CONTENT_URI, noteTypeId.toString()),
            "templates"
        )
        val templates = mutableListOf<AnkiCardTemplate>()
        try {
            appContext.contentResolver.query(templatesUri, null, null, null, null)?.use { cursor ->
                val ordIdx = cursor.getColumnIndex(FlashCardsContract.CardTemplate.ORD)
                val nameIdx = cursor.getColumnIndex(FlashCardsContract.CardTemplate.NAME)
                val qIdx = cursor.getColumnIndex(FlashCardsContract.CardTemplate.QUESTION_FORMAT)
                val aIdx = cursor.getColumnIndex(FlashCardsContract.CardTemplate.ANSWER_FORMAT)
                while (cursor.moveToNext()) {
                    val ordinal = if (ordIdx >= 0) cursor.getInt(ordIdx) else templates.size
                    val tName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    val front = if (qIdx >= 0) cursor.getString(qIdx) else null
                    val back = if (aIdx >= 0) cursor.getString(aIdx) else null
                    templates.add(
                        AnkiCardTemplate(
                            ordinal = ordinal,
                            name = tName ?: "Card ${templates.size + 1}",
                            frontTemplate = front,
                            backTemplate = back
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // 模板只读能力依赖 AnkiDroid 版本；读不到则忽略，返回空列表。
            logRepoWarn("读取卡片模板失败（best-effort）: ${e.message}")
        }
        return templates
    }

    override suspend fun addNote(request: AddGenericNoteRequest): AddGenericNoteResult =
        withAnkiRetry { doAddGenericNote(request) }

    private suspend fun doAddGenericNote(request: AddGenericNoteRequest): AddGenericNoteResult =
        withContext(Dispatchers.IO) {
            ensureAvailable()
            val noteTypeId = request.noteTypeId
            if (noteTypeId <= 0) {
                throw ModelNotFoundException("笔记类型不存在或 noteTypeId 非法: $noteTypeId")
            }

            val deck = ensureDeck(request.deck)
            // 请求参数摘要（仅记录 deck / noteTypeId / 数量，绝不记录字段内容、标签或 Token）。
            logRepo.info("add_note 请求参数摘要: deck=${deck.name}, deckCreated=${deck.created}, noteTypeId=$noteTypeId, noteCount=1")
            val orderedFields = getFieldList(noteTypeId)?.toList()
                ?: throw ModelNotFoundException("笔记类型不存在或无法读取字段: $noteTypeId")

            // 字段映射：严格优先、忽略大小写、拒绝未知字段、至少一个非空。
            // 字段映射错误（未知字段 / 歧义 / 全空）直接抛出，由工具层转为带错误码的业务错误（isError=true）。
            val tags = request.tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val fieldValues = mapNoteFields(orderedFields, request.fields).toList()
            val noteId = addGenericNoteRow(noteTypeId, deck.id, fieldValues, tags)
                ?: return@withContext AddGenericNoteResult(
                    success = false, noteId = null, deck = deck.name,
                    noteTypeId = noteTypeId, persisted = false, refreshNotified = false,
                    deckId = deck.id, deckCreated = deck.created
                ).also { logRepoWarn("插入笔记失败: $noteTypeId") }

            logRepo.info("Note 插入成功: noteId=$noteId")

            // 写入后回读验证持久化（≤3 次重试，间隔 150ms）
            val persisted = verifyNotePersisted(noteId, noteTypeId, fieldValues)
            if (persisted) {
                logRepo.info("Note 回读验证成功: noteId=$noteId")
            } else {
                logRepoWarn("Note 回读验证失败（已写入但无法读回）: noteId=$noteId")
            }

            // 本地刷新通知（不保证 AnkiDroid 当前页立即刷新；非 AnkiWeb 云同步）
            val refreshNotified = notifyAnkiChanged()
            if (refreshNotified) {
                logRepo.info("已发送 AnkiDroid 数据刷新通知")
            }

            AddGenericNoteResult(
                success = true,
                noteId = noteId,
                deck = deck.name,
                noteTypeId = noteTypeId,
                persisted = persisted,
                refreshNotified = refreshNotified,
                deckId = deck.id,
                deckCreated = deck.created
            )
        }

    override suspend fun addNotes(request: AddGenericNotesRequest): BatchAddGenericResult =
        withAnkiRetry { doAddGenericNotes(request) }

    override suspend fun findNotes(query: String): List<Long> = withAnkiRetry {
        withContext(Dispatchers.IO) {
            ensureAvailable()
            val result = mutableListOf<Long>()
            val selection = query.trim().takeIf { it.isNotEmpty() }
            appContext.contentResolver.query(
                FlashCardsContract.Note.CONTENT_URI,
                arrayOf(FlashCardsContract.Note._ID),
                selection,
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note._ID)
                while (cursor.moveToNext() && result.size < 500) {
                    result.add(cursor.getLong(idIdx))
                }
            }
            result
        }
    }

    override suspend fun notesInfo(noteIds: List<Long>): List<AnkiNoteInfo> = withAnkiRetry {
        withContext(Dispatchers.IO) {
            ensureAvailable()
            noteIds.take(100).mapNotNull { readNoteInfo(it) }
        }
    }

    override suspend fun findDuplicateNotes(noteTypeId: Long, firstFieldValue: String): List<Long> = withAnkiRetry {
        withContext(Dispatchers.IO) {
            ensureAvailable()
            val firstField = firstFieldValue.trim()
            if (noteTypeId <= 0 || firstField.isEmpty()) return@withContext emptyList()
            val result = mutableListOf<Long>()
            appContext.contentResolver.query(
                FlashCardsContract.Note.CONTENT_URI,
                arrayOf(FlashCardsContract.Note._ID, FlashCardsContract.Note.MID, FlashCardsContract.Note.FLDS),
                "${FlashCardsContract.Note.MID} = ?",
                arrayOf(noteTypeId.toString()),
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note._ID)
                val fldsIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.FLDS)
                while (cursor.moveToNext() && result.size < 100) {
                    val storedFirst = (cursor.getString(fldsIdx) ?: "")
                        .split(FIELD_SEPARATOR, limit = 2)
                        .firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (storedFirst == firstField) {
                        result.add(cursor.getLong(idIdx))
                    }
                }
            }
            result
        }
    }

    override suspend fun updateNoteFields(noteId: Long, fields: Map<String, String>): Boolean = withAnkiRetry {
        withContext(Dispatchers.IO) {
            ensureAvailable()
            val current = readNoteInfo(noteId) ?: return@withContext false
            val ordered = getFieldList(current.noteTypeId)?.toList() ?: return@withContext false
            val merged = current.fields.toMutableMap()
            fields.forEach { (key, value) ->
                val actual = ordered.firstOrNull { it == key }
                    ?: ordered.firstOrNull { it.equals(key, ignoreCase = true) }
                    ?: throw FieldMappingException(AnkiErrors.FIELD_NOT_FOUND, "字段不存在: $key")
                merged[actual] = value
            }
            val values = ContentValues().apply {
                put(FlashCardsContract.Note.FLDS, ordered.map { merged[it].orEmpty() }.joinToString(FIELD_SEPARATOR))
            }
            val uri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteId.toString())
            val updated = appContext.contentResolver.update(uri, values, null, null)
            if (updated > 0) notifyAnkiChanged()
            updated > 0
        }
    }

    override suspend fun getTags(pattern: String?): List<String> = withAnkiRetry {
        withContext(Dispatchers.IO) {
            ensureAvailable()
            val filter = pattern?.trim()?.takeIf { it.isNotEmpty() }
            val tags = linkedSetOf<String>()
            appContext.contentResolver.query(
                FlashCardsContract.Note.CONTENT_URI,
                arrayOf(FlashCardsContract.Note.TAGS),
                null,
                null,
                null
            )?.use { cursor ->
                val tagsIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.TAGS)
                while (cursor.moveToNext()) {
                    parseTags(cursor.getString(tagsIdx)).forEach { tag ->
                        if (filter == null || tag.contains(filter, ignoreCase = true)) tags.add(tag)
                    }
                }
            }
            tags.sorted()
        }
    }

    override suspend fun addTags(noteIds: List<Long>, tags: List<String>): Int = withAnkiRetry {
        updateTags(noteIds, tags, add = true)
    }

    override suspend fun removeTags(noteIds: List<Long>, tags: List<String>): Int = withAnkiRetry {
        updateTags(noteIds, tags, add = false)
    }

    override suspend fun replaceTags(noteIds: List<Long>, tagToReplace: String, replaceWithTag: String): Int = withAnkiRetry {
        withContext(Dispatchers.IO) {
            ensureAvailable()
            val from = tagToReplace.trim()
            val to = replaceWithTag.trim()
            if (from.isEmpty() || to.isEmpty() || from.contains(" ") || to.contains(" ")) {
                throw IllegalArgumentException("tagToReplace/replaceWithTag must be single non-empty tags")
            }
            var updatedCount = 0
            noteIds.take(100).forEach { noteId ->
                val current = readNoteInfo(noteId) ?: return@forEach
                if (!current.tags.contains(from)) return@forEach
                val nextTags = current.tags.map { if (it == from) to else it }.distinct()
                val values = ContentValues().apply {
                    put(FlashCardsContract.Note.TAGS, nextTags.joinToString(" "))
                }
                val uri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteId.toString())
                if (appContext.contentResolver.update(uri, values, null, null) > 0) {
                    updatedCount++
                }
            }
            if (updatedCount > 0) notifyAnkiChanged()
            updatedCount
        }
    }

    override suspend fun getCards(deckName: String?, cardState: String?, limit: Int): List<AnkiCardInfo> = withAnkiRetry {
        withContext(Dispatchers.IO) {
            ensureAvailable()
            val deckNamesById = deckNamesById()
            val deckId = deckName?.trim()?.takeIf { it.isNotEmpty() }?.let { target ->
                deckList()[target.lowercase()] ?: return@withContext emptyList()
            }
            val result = mutableListOf<AnkiCardInfo>()
            appContext.contentResolver.query(
                FlashCardsContract.Card.CONTENT_URI,
                null,
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext() && result.size < limit.coerceAtLeast(1)) {
                    val card = cursor.toCardInfo(deckNamesById) ?: continue
                    if (deckId != null && card.deckId != deckId) continue
                    if (!matchesCardState(card, cardState)) continue
                    result.add(card)
                }
            }
            result
        }
    }

    override suspend fun getDueCards(deckName: String?, limit: Int): List<AnkiCardInfo> = withAnkiRetry {
        withContext(Dispatchers.IO) {
            ensureAvailable()
            readDueCards(deckName, limit.coerceIn(1, 100))
        }
    }

    override suspend fun presentCard(cardId: Long): AnkiCardInfo? = withAnkiRetry {
        withContext(Dispatchers.IO) {
            ensureAvailable()
            readCardInfo(cardId, deckNamesById())
        }
    }

    override suspend fun changeDeck(cardIds: List<Long>, deckName: String): Int = withAnkiRetry {
        withContext(Dispatchers.IO) {
            ensureAvailable()
            val deck = ensureDeck(deckName)
            var updated = 0
            cardIds.take(100).forEach { cardId ->
                val uri = Uri.withAppendedPath(FlashCardsContract.Card.CONTENT_URI, cardId.toString())
                val values = ContentValues().apply {
                    put(FlashCardsContract.Card.DECK_ID, deck.id)
                }
                if (appContext.contentResolver.update(uri, values, null, null) > 0) updated++
            }
            if (updated > 0) notifyAnkiChanged()
            updated
        }
    }

    override suspend fun rateCard(cardId: Long, rating: Int, timeTakenMs: Long): Boolean = withAnkiRetry {
        withContext(Dispatchers.IO) {
            ensureAvailable()
            if (rating !in 1..4) throw IllegalArgumentException("rating must be 1..4")
            val card = readCardInfo(cardId, deckNamesById()) ?: return@withContext false
            val values = ContentValues().apply {
                put(FlashCardsContract.ReviewInfo.NOTE_ID, card.noteId)
                put(FlashCardsContract.ReviewInfo.CARD_ORD, card.ord)
                put(FlashCardsContract.ReviewInfo.EASE, rating)
                put(FlashCardsContract.ReviewInfo.TIME_TAKEN, timeTakenMs.coerceAtLeast(0L))
            }
            val updated = appContext.contentResolver.update(
                FlashCardsContract.ReviewInfo.CONTENT_URI,
                values,
                null,
                null
            )
            if (updated > 0) notifyAnkiChanged()
            updated > 0
        }
    }

    override suspend fun suspendCards(cardIds: List<Long>): Int = withAnkiRetry {
        withContext(Dispatchers.IO) {
            ensureAvailable()
            val deckNamesById = deckNamesById()
            var updated = 0
            cardIds.take(100).forEach { cardId ->
                val card = readCardInfo(cardId, deckNamesById) ?: return@forEach
                val values = ContentValues().apply {
                    put(FlashCardsContract.ReviewInfo.NOTE_ID, card.noteId)
                    put(FlashCardsContract.ReviewInfo.CARD_ORD, card.ord)
                    put(FlashCardsContract.ReviewInfo.SUSPEND, 1)
                }
                if (
                    appContext.contentResolver.update(
                        FlashCardsContract.ReviewInfo.CONTENT_URI,
                        values,
                        null,
                        null
                    ) > 0
                ) {
                    updated++
                }
            }
            if (updated > 0) notifyAnkiChanged()
            updated
        }
    }

    override suspend fun areSuspended(cardIds: List<Long>): List<Boolean> = withAnkiRetry {
        withContext(Dispatchers.IO) {
            ensureAvailable()
            val deckNamesById = deckNamesById()
            cardIds.map { cardId -> readCardInfo(cardId, deckNamesById)?.queue == -1 }
        }
    }

    override suspend fun areDue(cardIds: List<Long>): List<Boolean> = withAnkiRetry {
        withContext(Dispatchers.IO) {
            ensureAvailable()
            val dueIds = readDueCards(deckName = null, limit = Int.MAX_VALUE).map { it.id }.toSet()
            cardIds.map { it in dueIds }
        }
    }

    override suspend fun getIntervals(cardIds: List<Long>): List<Int> = withAnkiRetry {
        withContext(Dispatchers.IO) {
            ensureAvailable()
            val deckNamesById = deckNamesById()
            cardIds.map { cardId -> readCardInfo(cardId, deckNamesById)?.interval ?: 0 }
        }
    }

    private suspend fun doAddGenericNotes(request: AddGenericNotesRequest): BatchAddGenericResult =
        withContext(Dispatchers.IO) {
            ensureAvailable()
            val deck = ensureDeck(request.deck)
            // 请求参数摘要（仅记录 deck / 数量，绝不记录字段内容、标签或 Token）。
            logRepo.info("add_notes 请求参数摘要: deck=${deck.name}, deckCreated=${deck.created}, noteCount=${request.notes.size}")

            // 1) 预校验 + 构造按模型分组的写入计划，记录原始下标与错误
            val errors = mutableListOf<BatchError>()
            val validPlans = mutableListOf<GenericPlan>()

            request.notes.forEachIndexed { index, item ->
                when {
                    item.noteTypeId <= 0 ->
                        errors.add(BatchError(index, AnkiErrors.INVALID_NOTE_TYPE_ID, "第 ${index + 1} 项 noteTypeId 非法"))
                    else -> {
                        val orderedFields = getFieldList(item.noteTypeId)?.toList()
                        if (orderedFields == null) {
                            errors.add(
                                BatchError(
                                    index, AnkiErrors.NOTE_TYPE_NOT_FOUND,
                                    "第 ${index + 1} 项笔记类型不存在或无法读取字段（noteTypeId=${item.noteTypeId}）"
                                )
                            )
                            return@forEachIndexed
                        }
                        val fieldValues = try {
                            mapNoteFields(orderedFields, item.fields).toList()
                        } catch (e: FieldMappingException) {
                            errors.add(BatchError(index, e.code, "第 ${index + 1} 项: ${e.message}"))
                            return@forEachIndexed
                        }
                        val tags = item.tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
                        validPlans.add(GenericPlan(index, item.noteTypeId, fieldValues, tags))
                    }
                }
            }

            val requested = request.notes.size
            val submitted = validPlans.size

            // 全部预校验失败：没有任何条目进入插入流程，failed=requested（而非 errors.size 之外无其他约束）
            if (submitted == 0) {
                return@withContext BatchAddGenericResult(
                    requested = requested, submitted = 0, succeeded = 0,
                    failed = calculateBatchFailed(requested, 0),
                    noteIds = emptyList(), noteIdsAvailable = false,
                    errors = errors, persisted = false, refreshNotified = false,
                    deckId = deck.id, deckCreated = deck.created
                )
            }

            // 2) 按 noteTypeId 分组，逐模型单独 bulkInsert（避免一次全局混合 bulkInsert 无法区分各模型成功数）
            val groups: Map<Long, ModelBatchPlan<ContentValues>> = validPlans
                .groupBy { it.noteTypeId }
                .mapValues { (noteTypeId, plans) ->
                    ModelBatchPlan(
                        noteTypeId = noteTypeId,
                        values = plans.map { buildGenericValues(it.noteTypeId, it.fields, it.tags) },
                        originalIndexes = plans.map { it.index }
                    )
                }

            return@withContext addMutex.withLock {
                // 逐模型批量插入（纯函数协调器，真实插入由 lambda 注入）
                val summary = executeBatchInsert(groups) { _, values ->
                    appContext.contentResolver.bulkInsert(
                        FlashCardsContract.Note.CONTENT_URI, values.toTypedArray()
                    )
                }

                // 3) 只移动各模型“实际插入成功”的数量（修正部分插入时误移写入前的旧卡片）
                for ((noteTypeId, inserted) in summary.insertedByModel) {
                    if (inserted > 0) moveRecentNotesToDeck(noteTypeId, inserted, deck.id)
                }

                // 4) 回读验证持久化（基于各模型实际插入数量，best-effort）
                val persisted = verifyRecentNotesPersisted(summary.insertedByModel)

                // 5) 本地刷新通知
                val refreshNotified = notifyAnkiChanged()

                val succeeded = summary.totalInserted
                BatchAddGenericResult(
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
        }

    // ── 通用写入辅助 ──

    /** 插入一条通用笔记（按笔记类型字段顺序），返回 noteId。 */
    private fun addGenericNoteRow(
        modelId: Long,
        deckId: Long,
        fields: List<String>,
        tags: List<String>
    ): Long? {
        val values = buildGenericValues(modelId, fields, tags)
        val newNoteUri = appContext.contentResolver.insert(FlashCardsContract.Note.CONTENT_URI, values)
            ?: return null
        val noteId = newNoteUri.lastPathSegment?.toLongOrNull() ?: return null
        moveNoteCardsToDeck(noteId, deckId)
        return noteId
    }

    private suspend fun readNoteInfo(noteId: Long): AnkiNoteInfo? {
        val uri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteId.toString())
        return appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val idIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note._ID)
            val midIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.MID)
            val fldsIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.FLDS)
            val tagsIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.TAGS)
            val id = cursor.getLong(idIdx)
            val mid = cursor.getLong(midIdx)
            val detail = try {
                doGetNoteType(mid)
            } catch (e: Exception) {
                null
            }
            val orderedFields = detail?.fields ?: getFieldList(mid)?.toList().orEmpty()
            val storedFields = (cursor.getString(fldsIdx) ?: "").split(FIELD_SEPARATOR)
            val fields = orderedFields.mapIndexed { index, fieldName ->
                fieldName to storedFields.getOrElse(index) { "" }
            }.toMap()
            AnkiNoteInfo(
                id = id,
                noteTypeId = mid,
                modelName = detail?.name.orEmpty(),
                fields = fields,
                tags = parseTags(cursor.getString(tagsIdx)),
                css = detail?.css
            )
        }
    }

    private suspend fun updateTags(noteIds: List<Long>, tags: List<String>, add: Boolean): Int =
        withContext(Dispatchers.IO) {
            ensureAvailable()
            val cleanTags = tags.map { it.trim() }.filter { it.isNotBlank() }
            if (cleanTags.isEmpty()) return@withContext 0
            var updatedCount = 0
            noteIds.take(100).forEach { noteId ->
                val current = readNoteInfo(noteId) ?: return@forEach
                val nextTags = if (add) {
                    (current.tags + cleanTags).distinct()
                } else {
                    val remove = cleanTags.toSet()
                    current.tags.filterNot { remove.contains(it) }
                }
                val values = ContentValues().apply {
                    put(FlashCardsContract.Note.TAGS, nextTags.joinToString(" "))
                }
                val uri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteId.toString())
                if (appContext.contentResolver.update(uri, values, null, null) > 0) {
                    updatedCount++
                }
            }
            if (updatedCount > 0) notifyAnkiChanged()
            updatedCount
        }

    private fun readCardInfo(cardId: Long, deckNamesById: Map<Long, String>): AnkiCardInfo? {
        val uri = Uri.withAppendedPath(FlashCardsContract.Card.CONTENT_URI, cardId.toString())
        return appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.toCardInfo(deckNamesById)
        }
    }

    private fun readCardInfoByNoteOrd(noteId: Long, ord: Int, deckNamesById: Map<Long, String>): AnkiCardInfo? {
        val noteUri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteId.toString())
        val cardsUri = Uri.withAppendedPath(noteUri, "cards")
        val cardUri = Uri.withAppendedPath(cardsUri, ord.toString())
        return appContext.contentResolver.query(cardUri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.toCardInfo(deckNamesById)
        }
    }

    private fun readDueCards(deckName: String?, limit: Int): List<AnkiCardInfo> {
        val deckNamesById = deckNamesById()
        val args = mutableListOf(limit.coerceAtLeast(1).toString())
        var selection = "limit=?"
        deckName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            val deckId = deckList()[name.lowercase()] ?: return emptyList()
            selection += ", deckID=?"
            args.add(deckId.toString())
        }
        val result = mutableListOf<AnkiCardInfo>()
        appContext.contentResolver.query(
            FlashCardsContract.ReviewInfo.CONTENT_URI,
            null,
            selection,
            args.toTypedArray(),
            null
        )?.use { cursor ->
            val noteIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.ReviewInfo.NOTE_ID)
            val ordIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.ReviewInfo.CARD_ORD)
            while (cursor.moveToNext()) {
                readCardInfoByNoteOrd(cursor.getLong(noteIdx), cursor.getInt(ordIdx), deckNamesById)?.let { result.add(it) }
            }
        }
        return result
    }

    private fun android.database.Cursor.toCardInfo(deckNamesById: Map<Long, String>): AnkiCardInfo? {
        val idIdx = getColumnIndex(FlashCardsContract.Card._ID)
        val noteIdx = getColumnIndex(FlashCardsContract.Card.NOTE_ID)
        val ordIdx = getColumnIndex(FlashCardsContract.Card.CARD_ORD)
        val deckIdx = getColumnIndex(FlashCardsContract.Card.DECK_ID)
        if (idIdx < 0 || noteIdx < 0 || ordIdx < 0 || deckIdx < 0) return null
        val deckId = getLong(deckIdx)
        return AnkiCardInfo(
            id = getLong(idIdx),
            noteId = getLong(noteIdx),
            ord = getInt(ordIdx),
            deckId = deckId,
            deckName = deckNamesById[deckId].orEmpty(),
            cardName = getOptionalString(FlashCardsContract.Card.CARD_NAME).orEmpty(),
            question = getOptionalString(FlashCardsContract.Card.QUESTION).orEmpty(),
            answer = getOptionalString(FlashCardsContract.Card.ANSWER).orEmpty(),
            questionSimple = getOptionalString(FlashCardsContract.Card.QUESTION_SIMPLE).orEmpty(),
            answerSimple = getOptionalString(FlashCardsContract.Card.ANSWER_SIMPLE).orEmpty(),
            answerPure = getOptionalString(FlashCardsContract.Card.ANSWER_PURE).orEmpty(),
            type = getOptionalInt(FlashCardsContract.Card.TYPE),
            queue = getOptionalInt(FlashCardsContract.Card.RAW_QUEUE),
            due = getOptionalLong(FlashCardsContract.Card.RAW_DUE),
            interval = getOptionalInt(FlashCardsContract.Card.INTERVAL),
            easeFactor = getOptionalInt(FlashCardsContract.Card.RAW_SM2_FACTOR),
            reps = getOptionalInt(FlashCardsContract.Card.REPS),
            lapses = getOptionalInt(FlashCardsContract.Card.LAPSES)
        )
    }

    private fun android.database.Cursor.getOptionalString(column: String): String? {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getString(idx) else null
    }

    private fun android.database.Cursor.getOptionalInt(column: String): Int? {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getInt(idx) else null
    }

    private fun android.database.Cursor.getOptionalLong(column: String): Long? {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getLong(idx) else null
    }

    private fun matchesCardState(card: AnkiCardInfo, state: String?): Boolean {
        return when (state?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }) {
            null -> true
            "new" -> card.queue == 0
            "learning" -> card.queue == 1 || card.queue == 3
            "due", "review" -> card.queue == 2
            "suspended" -> card.queue == -1
            "buried" -> card.queue == -2 || card.queue == -3
            else -> true
        }
    }

    private fun parseTags(raw: String?): List<String> =
        raw.orEmpty().split(" ").map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun buildGenericValues(
        modelId: Long,
        fields: List<String>,
        tags: List<String>
    ): ContentValues {
        return ContentValues().apply {
            put(FlashCardsContract.Note.MID, modelId)
            put(FlashCardsContract.Note.FLDS, fields.joinToString(FIELD_SEPARATOR))
            if (tags.isNotEmpty()) {
                put(FlashCardsContract.Note.TAGS, tags.joinToString(" "))
            }
        }
    }

    /**
     * 写入后回读验证：noteId 存在、MID 与请求一致、非空字段能与存储内容逐位匹配。
     * 最多重试 3 次（首次立即，之后间隔 150ms）。
     */
    /**
     * 写入后回读验证：noteId 存在、MID 与请求一致、非空字段能与存储内容逐位匹配。
     * 最多重试 3 次（首次立即，之后间隔 150ms）。挂起式等待，不阻塞线程。
     */
    private suspend fun verifyNotePersisted(noteId: Long, modelId: Long, fields: List<String>): Boolean {
        repeat(3) { attempt ->
            val uri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteId.toString())
            val ok = appContext.contentResolver.query(
                uri, arrayOf(FlashCardsContract.Note.MID, FlashCardsContract.Note.FLDS), null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val mid = cursor.getLong(cursor.getColumnIndexOrThrow(FlashCardsContract.Note.MID))
                if (mid != modelId) return@use false
                val storedRaw = cursor.getString(cursor.getColumnIndexOrThrow(FlashCardsContract.Note.FLDS)) ?: ""
                val stored = storedRaw.split(FIELD_SEPARATOR)
                // 仅校验非空字段：逐位与存储内容一致（AnkiDroid 通常原样存储字段）
                fields.forEachIndexed { i, f ->
                    if (f.isNotBlank() && stored.getOrNull(i) != f) return@use false
                }
                true
            } ?: false
            if (ok) return true
            // 挂起式等待（不阻塞线程），后两次间隔 150ms
            if (attempt < 2) delay(150)
        }
        return false
    }

    /**
     * 批量路径的持久化验证（best-effort）：对每个实际插入的模型，查询 ContentProvider 能读回的
     * 最新笔记数量是否 >= 该模型实际插入数量。不依赖“计划数量”或“全局总插入数”。
     */
    private fun verifyRecentNotesPersisted(insertedByModel: Map<Long, Int>): Boolean {
        if (insertedByModel.isEmpty()) return false
        val readCounts = mutableMapOf<Long, Int>()
        for ((modelId, expectedCount) in insertedByModel) {
            if (expectedCount <= 0) continue
            var found = 0
            appContext.contentResolver.query(
                FlashCardsContract.Note.CONTENT_URI,
                arrayOf(FlashCardsContract.Note._ID, FlashCardsContract.Note.MID),
                "${FlashCardsContract.Note.MID} = ?",
                arrayOf(modelId.toString()),
                "${FlashCardsContract.Note._ID} DESC"
            )?.use { cursor ->
                val midIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.MID)
                while (cursor.moveToNext() && found < expectedCount) {
                    val mid = cursor.getLong(midIdx)
                    if (mid == modelId) found++
                }
            } ?: return false
            readCounts[modelId] = found
        }
        return checkBatchPersisted(insertedByModel, readCounts)
    }

    /**
     * 本地刷新通知：通过 [android.content.ContentResolver.notifyChange] 通知 AnkiDroid 数据变化。
     * 仅使用官方 [FlashCardsContract] 中真实存在的 URI。
     * 注意：这是本地数据变更通知，不是 AnkiWeb 云同步。
     */
    private fun notifyAnkiChanged(): Boolean {
        return try {
            val cr = appContext.contentResolver
            cr.notifyChange(FlashCardsContract.Note.CONTENT_URI, null)
            cr.notifyChange(FlashCardsContract.Deck.CONTENT_ALL_URI, null)
            cr.notifyChange(FlashCardsContract.Card.CONTENT_URI, null)
            true
        } catch (e: Exception) {
            logRepoWarn("刷新通知失败（不影响写入）: ${e.message}")
            false
        }
    }

    private fun logRepoWarn(message: String) {
        // AnkiDroidRepository 没有直接的日志出口，复用 AppLogRepository 单例。
        AppLogRepository.instance.warn(message)
    }

    private suspend fun doAddBasicNotes(request: AddBasicNotesRequest): BatchAddResult =
        withContext(Dispatchers.IO) {
            ensureAvailable()
            val deck = ensureDeck(request.deck)
            // 请求参数摘要（仅记录 deck / 数量，绝不记录字段内容、标签或 Token）。
            logRepo.info("add_basic_notes 请求参数摘要: deck=${deck.name}, deckCreated=${deck.created}, noteCount=${request.notes.size}")
            val modelId = AnkiModelResolver.resolveBasicModelId(appContext)

            // 1) 预校验：不通过校验的卡片不会进入批量插入，记录原始下标。
            val valuesList = mutableListOf<ContentValues>()
            val errors = mutableListOf<BatchError>()
            request.notes.forEachIndexed { index, note ->
                when {
                    note.front.isBlank() ->
                        errors.add(BatchError(index, AnkiErrors.INVALID_FRONT, "第 ${index + 1} 张卡片 front 不能为空"))
                    note.back.isBlank() ->
                        errors.add(BatchError(index, AnkiErrors.INVALID_BACK, "第 ${index + 1} 张卡片 back 不能为空"))
                    note.front.length > 10000 ->
                        errors.add(BatchError(index, AnkiErrors.INVALID_FRONT, "第 ${index + 1} 张卡片 front 过长"))
                    note.back.length > 10000 ->
                        errors.add(BatchError(index, AnkiErrors.INVALID_BACK, "第 ${index + 1} 张卡片 back 过长"))
                    else -> valuesList.add(buildNoteValues(modelId, note))
                }
            }

            val submitted = valuesList.size
            if (submitted == 0) {
                return@withContext BatchAddResult(
                    requested = request.notes.size,
                    submitted = 0,
                    succeeded = 0,
                    failed = errors.size,
                    noteIds = emptyList(),
                    noteIdsAvailable = false,
                    errors = errors
                )
            }

            // 2) 真批量插入：单次 bulkInsert（等价官方 AddContentApi.addNotes），而非循环 insert。
            val valuesArray = valuesList.toTypedArray()
            addMutex.withLock {
                val inserted = appContext.contentResolver.bulkInsert(
                    FlashCardsContract.Note.CONTENT_URI, valuesArray
                )
                if (inserted < 0) {
                    // 批量整体失败：bulkInsert 返回负值。
                    return@withLock BatchAddResult(
                        requested = request.notes.size,
                        submitted = submitted,
                        succeeded = 0,
                        failed = request.notes.size,
                        noteIds = emptyList(),
                        noteIdsAvailable = false,
                        errors = errors + BatchError(
                            -1, AnkiErrors.BATCH_FAILED, "批量插入失败（bulkInsert 返回 $inserted）"
                        )
                    )
                }

                // 3) 把本次插入的卡片移动到目标牌组（best-effort：取本模型下最新 inserted 张笔记）。
                moveRecentNotesToDeck(modelId, inserted, deck.id)

                val partial = if (inserted < submitted) {
                    listOf(
                        BatchError(
                            -1, AnkiErrors.PARTIAL_FAILURE,
                            "部分失败：submitted=$submitted, succeeded=$inserted"
                        )
                    )
                } else {
                    emptyList()
                }
                return@withLock BatchAddResult(
                    requested = request.notes.size,
                    submitted = submitted,
                    succeeded = inserted,
                    failed = submitted - inserted,
                    noteIds = emptyList(),
                    noteIdsAvailable = false,
                    errors = errors + partial
                )
            }
        }

    // ───────────────────────────────────────────────────────────
    // 官方 AddContentApi 等价能力
    // ───────────────────────────────────────────────────────────

    /** 等价 [AddContentApi.deckList]：返回 deckName(小写) -> deckId 的映射。 */
    private fun deckList(): Map<String, Long> {
        return readDecks().associate { it.name.lowercase() to it.id }
    }

    private fun deckNamesById(): Map<Long, String> {
        return readDecks().associate { it.id to it.name }
    }

    private fun readDecks(): List<AnkiDeck> {
        val decks = mutableListOf<AnkiDeck>()
        appContext.contentResolver.query(
            FlashCardsContract.Deck.CONTENT_ALL_URI, null, null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Deck.DECK_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Deck.DECK_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val name = cursor.getString(nameIdx) ?: continue
                decks.add(AnkiDeck(id = id, name = name))
            }
        }
        return decks
    }

    /** 等价 [AddContentApi.addNewDeck]。 */
    private fun addNewDeck(deckName: String): Long? {
        val values = ContentValues().apply {
            put(FlashCardsContract.Deck.DECK_NAME, deckName)
        }
        val uri = appContext.contentResolver.insert(
            FlashCardsContract.Deck.CONTENT_ALL_URI, values
        ) ?: return null
        return uri.lastPathSegment?.toLongOrNull()
    }

    /** 等价 [AddContentApi.modelList]：返回 modelId -> modelName 的映射。 */
    private fun modelList(): Map<Long, String> {
        val map = mutableMapOf<Long, String>()
        appContext.contentResolver.query(
            FlashCardsContract.Model.CONTENT_URI, null, null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Model._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Model.NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val name = cursor.getString(nameIdx) ?: ""
                map[id] = name
            }
        }
        return map
    }

    /** 等价 [AddContentApi.getFieldList]：返回指定模型的字段名数组。 */
    private fun getFieldList(modelId: Long): Array<String>? {
        val uri = Uri.withAppendedPath(
            FlashCardsContract.Model.CONTENT_URI, modelId.toString()
        )
        return appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val idx = cursor.getColumnIndexOrThrow(FlashCardsContract.Model.FIELD_NAMES)
            val raw = cursor.getString(idx) ?: return@use null
            AnkiModelResolver.parseFieldNames(raw)
        }
    }

    /** 等价 [AddContentApi.addNewBasicModel]。 */
    private fun addNewBasicModel(name: String): Long? {
        return AnkiModelResolver.createBasicModel(appContext, name)
    }

    /** 构造一条 Basic 笔记的 ContentValues（front/back 以 [FIELD_SEPARATOR] 连接）。 */
    private fun buildNoteValues(modelId: Long, note: SingleNoteRequest): ContentValues {
        return ContentValues().apply {
            put(FlashCardsContract.Note.MID, modelId)
            put(FlashCardsContract.Note.FLDS, arrayOf(note.front, note.back).joinToString(FIELD_SEPARATOR))
            if (note.tags.isNotEmpty()) {
                put(FlashCardsContract.Note.TAGS, note.tags.joinToString(" "))
            }
        }
    }

    /** 将某条笔记产生的所有卡片移动到目标牌组。 */
    private fun moveNoteCardsToDeck(noteId: Long, deckId: Long) {
        val newNoteUri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteId.toString())
        val cardsUri = Uri.withAppendedPath(newNoteUri, "cards")
        appContext.contentResolver.query(cardsUri, null, null, null, null)?.use { cardsCursor ->
            val ordIdx = cardsCursor.getColumnIndexOrThrow(FlashCardsContract.Card.CARD_ORD)
            while (cardsCursor.moveToNext()) {
                val ord = cardsCursor.getString(ordIdx) ?: continue
                val cardUri = Uri.withAppendedPath(cardsUri, ord)
                val cardValues = ContentValues().apply {
                    put(FlashCardsContract.Card.DECK_ID, deckId)
                }
                appContext.contentResolver.update(cardUri, cardValues, null, null)
            }
        }
    }

    /**
     * 批量路径下的卡片移组：取 [modelId] 下最新 [count] 张笔记（按 `_id DESC`），
     * 把它们的卡片移动到 [deckId]。在 [addMutex] 保护下调用，保证“最新 N 张”即本次批量写入。
     */
    private fun moveRecentNotesToDeck(modelId: Long, count: Int, deckId: Long) {
        if (count <= 0) return
        val selection = "${FlashCardsContract.Note.MID} = ?"
        val selectionArgs = arrayOf(modelId.toString())
        appContext.contentResolver.query(
            FlashCardsContract.Note.CONTENT_URI, null, selection, selectionArgs,
            "${FlashCardsContract.Note._ID} DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note._ID)
            var taken = 0
            while (cursor.moveToNext() && taken < count) {
                val noteId = cursor.getLong(idIdx)
                moveNoteCardsToDeck(noteId, deckId)
                taken++
            }
        }
    }

    // ───────────────────────────────────────────────────────────
    // 运行时守卫：可用性检查 + 初始化重试
    // ───────────────────────────────────────────────────────────

    private fun ensureAvailable() {
        if (!isAnkiDroidInstalled()) throw AnkiDroidNotInstalledException()
        if (!hasPermission()) throw AnkiPermissionDeniedException()
    }

    /**
     * 包裹对 ContentProvider 的访问。针对“集合尚未初始化”类异常**仅重试一次**；
     * 其余异常（权限、未安装、一般性错误）不重试，直接按业务语义上抛。
     */
    private suspend fun <T> withAnkiRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (isInitException(e)) {
                delay(INIT_RETRY_DELAY_MS)
                try {
                    block()
                } catch (e2: Exception) {
                    rethrowAsAnki(e2)
                }
            } else {
                rethrowAsAnki(e)
            }
        }
    }

    /** 判断异常是否源于 AnkiDroid 集合尚未就绪（只需重试一次的场景）。 */
    private fun isInitException(e: Exception): Boolean {
        // 集合未初始化 / 仍在加载：AnkiDroid 首次访问 ContentProvider 可能抛出此类异常。
        val msg = e.message ?: return false
        return e is IllegalStateException &&
            (msg.contains("not initialized", ignoreCase = true) ||
                msg.contains("collection", ignoreCase = true) && msg.contains("init", ignoreCase = true))
    }

    private fun rethrowAsAnki(e: Exception): Nothing {
        when (e) {
            is AnkiDroidNotInstalledException,
            is AnkiPermissionDeniedException -> throw e
            is SecurityException -> throw AnkiPermissionDeniedException()
            else -> throw e
        }
    }
}

class AnkiDroidNotInstalledException : Exception("AnkiDroid 未安装")
class AnkiPermissionDeniedException : Exception("AnkiDroid 权限未授权")
class DeckOperationException(message: String) : Exception(message)
class AddNoteException(message: String) : Exception(message)

/** 写入后回读验证失败：noteId 已返回但数据未能从 ContentProvider 读回。 */
class PersistenceCheckException(message: String) : Exception(message)
