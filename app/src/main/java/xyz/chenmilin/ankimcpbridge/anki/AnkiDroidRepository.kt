package xyz.chenmilin.ankimcpbridge.anki

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import com.ichi2.anki.FlashCardsContract
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
 *    批量路径在 [Mutex] 保护下，取本次模型最新 [submitted] 张笔记（按 `_id DESC`）将其卡片
 *    移动到目标牌组，确保 `deck` 参数语义成立。
 *
 * 不依赖任何额外 JAR；Authority 固定为 `com.ichi2.anki.flashcards`。
 */
class AnkiDroidRepository(context: Context) : AnkiRepository {

    private val appContext = context.applicationContext
    /** 串行化所有“插入笔记 + 移动卡片”操作，保证批量路径的“最新 N 张”查询准确。 */
    private val addMutex = Mutex()

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
        deckList().map { (name, id) -> AnkiDeck(id = id, name = name) }
            .sortedBy { it.name }
    }

    override suspend fun ensureDeck(name: String): AnkiDeck = withAnkiRetry {
        doEnsureDeck(name)
    }

    private suspend fun doEnsureDeck(name: String): AnkiDeck = withContext(Dispatchers.IO) {
        ensureAvailable()
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) throw IllegalArgumentException("牌组名称不能为空")
        if (trimmedName.length > 200) throw IllegalArgumentException("牌组名称过长（最多200字符）")

        deckList()[trimmedName.lowercase()]?.let { deckId ->
            return@withContext AnkiDeck(id = deckId, name = trimmedName)
        }
        val deckId = addNewDeck(trimmedName)
            ?: throw DeckOperationException("创建牌组失败: $trimmedName")
        AnkiDeck(id = deckId, name = trimmedName)
    }

    override suspend fun addBasicNote(request: AddBasicNoteRequest): AddNoteResult =
        withAnkiRetry { doAddBasicNote(request) }

    private suspend fun doAddBasicNote(request: AddBasicNoteRequest): AddNoteResult =
        withContext(Dispatchers.IO) {
            ensureAvailable()
            val deck = ensureDeck(request.deck)
            val modelId = AnkiModelResolver.resolveBasicModelId(appContext)
            val noteId = addNote(
                modelId = modelId,
                deckId = deck.id,
                fields = arrayOf(request.front, request.back),
                tags = request.tags.toSet()
            ) ?: throw AddNoteException("添加卡片失败")
            AddNoteResult(success = true, noteId = noteId, deck = deck.name)
        }

    override suspend fun addBasicNotes(request: AddBasicNotesRequest): BatchAddResult =
        withAnkiRetry { doAddBasicNotes(request) }

    private suspend fun doAddBasicNotes(request: AddBasicNotesRequest): BatchAddResult =
        withContext(Dispatchers.IO) {
            ensureAvailable()
            val deck = ensureDeck(request.deck)
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
        val map = mutableMapOf<String, Long>()
        appContext.contentResolver.query(
            FlashCardsContract.Deck.CONTENT_ALL_URI, null, null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Deck.DECK_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Deck.DECK_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val name = cursor.getString(nameIdx) ?: continue
                map[name.lowercase()] = id
            }
        }
        return map
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

    /** 等价 [AddContentApi.addNote]：插入笔记并将其所有卡片移动到目标牌组。 */
    private fun addNote(
        modelId: Long,
        deckId: Long,
        fields: Array<String>,
        tags: Set<String>?
    ): Long? {
        val values = ContentValues().apply {
            put(FlashCardsContract.Note.MID, modelId)
            put(FlashCardsContract.Note.FLDS, fields.joinToString(FIELD_SEPARATOR))
            if (!tags.isNullOrEmpty()) {
                put(FlashCardsContract.Note.TAGS, tags.joinToString(" "))
            }
        }
        val newNoteUri = appContext.contentResolver.insert(
            FlashCardsContract.Note.CONTENT_URI, values
        ) ?: return null

        val noteId = newNoteUri.lastPathSegment?.toLongOrNull() ?: return null
        moveNoteCardsToDeck(noteId, deckId)
        return noteId
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
