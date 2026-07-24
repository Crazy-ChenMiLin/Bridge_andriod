package xyz.chenmilin.ankimcpbridge.anki

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 基于 AnkiDroid ContentProvider API 的实现。
 *
 * AnkiDroid 通过 ContentProvider 暴露 API，无需额外 JAR 依赖：
 * - ContentProvider Authority: com.ichi2.anki.flashcards
 * - 通过 content:// URI 访问牌组、笔记类型和卡片数据。
 *
 * 参考: AnkiDroid FlashCardsContract
 */
class AnkiDroidRepository(context: Context) : AnkiRepository {

    private val appContext = context.applicationContext

    // ─── ContentProvider URI ───
    private val baseUri = Uri.parse("content://com.ichi2.anki.flashcards/")

    companion object {
        const val ANKIDROID_PACKAGE = "com.ichi2.anki"
        private const val CONTENT_PROVIDER_AUTHORITY = "com.ichi2.anki.flashcards"
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
            // 尝试查询 content provider 来验证权限
            val uri = Uri.parse("content://$CONTENT_PROVIDER_AUTHORITY/decks/")
            val cursor = withTimeout(1000) {
                appContext.contentResolver.query(uri, null, null, null, null)
            }
            cursor?.close()
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun listDecks(): List<AnkiDeck> = withContext(Dispatchers.IO) {
        ensureAvailable()

        retryOnce {
            val decks = mutableListOf<AnkiDeck>()
            val uri = Uri.parse("content://$CONTENT_PROVIDER_AUTHORITY/decks/")
            val cursor = queryWithTimeout(uri, null, null, null, null)

            cursor?.use { c ->
                val idIdx = c.getColumnIndexOrThrow("_id")
                val nameIdx = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val name = c.getString(nameIdx) ?: "Deck-$id"
                    decks.add(AnkiDeck(id = id, name = name))
                }
            }
            decks.sortedBy { it.name }
        }
    }

    override suspend fun ensureDeck(name: String): AnkiDeck = withContext(Dispatchers.IO) {
        ensureAvailable()
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) throw IllegalArgumentException("牌组名称不能为空")
        if (trimmedName.length > 200) throw IllegalArgumentException("牌组名称过长（最多200字符）")

        retryOnce {
            // 先查询是否已存在
            val decks = listDecks()
            val existing = decks.find { it.name.equals(trimmedName, ignoreCase = true) }
            if (existing != null) return@retryOnce existing

            // 创建新牌组
            val uri = Uri.parse("content://$CONTENT_PROVIDER_AUTHORITY/decks/")
            val values = ContentValues().apply {
                put("name", trimmedName)
            }
            val resultUri = appContext.contentResolver.insert(uri, values)
                ?: throw DeckOperationException("创建牌组失败: $trimmedName")

            val deckId = resultUri.lastPathSegment?.toLongOrNull()
                ?: throw DeckOperationException("无法获取牌组 ID: $trimmedName")

            AnkiDeck(id = deckId, name = trimmedName)
        }
    }

    override suspend fun addBasicNote(request: AddBasicNoteRequest): AddNoteResult =
        withContext(Dispatchers.IO) {
            ensureAvailable()

            if (request.front.isBlank()) throw IllegalArgumentException("front must not be blank")
            if (request.back.isBlank()) throw IllegalArgumentException("back must not be blank")

            retryOnce {
                val deck = ensureDeck(request.deck)
                val modelId = AnkiModelResolver.resolveBasicModelId(appContext)

                val tags = cleanTags(request.tags).joinToString(" ")

                val uri = Uri.parse("content://$CONTENT_PROVIDER_AUTHORITY/notes/")
                val values = ContentValues().apply {
                    put("mid", modelId)
                    put("did", deck.id)
                    put("flds", "${request.front}\u001f${request.back}")
                    put("tags", tags)
                }

                val resultUri = appContext.contentResolver.insert(uri, values)
                    ?: throw AddNoteException("添加卡片失败")

                val noteId = resultUri.lastPathSegment?.toLongOrNull()
                    ?: throw AddNoteException("无法获取卡片 ID")

                AddNoteResult(success = true, noteId = noteId, deck = deck.name)
            }
        }

    override suspend fun addBasicNotes(request: AddBasicNotesRequest): BatchAddResult =
        withContext(Dispatchers.IO) {
            ensureAvailable()

            val deck = ensureDeck(request.deck)
            val modelId = AnkiModelResolver.resolveBasicModelId(appContext)

            val succeededIds = mutableListOf<Long>()
            val errors = mutableListOf<BatchError>()

            for ((index, note) in request.notes.withIndex()) {
                try {
                    if (note.front.isBlank()) {
                        errors.add(
                            BatchError(index, AnkiErrors.INVALID_FRONT, "第 ${index + 1} 张卡片 front 不能为空")
                        )
                        continue
                    }
                    if (note.back.isBlank()) {
                        errors.add(
                            BatchError(index, AnkiErrors.INVALID_BACK, "第 ${index + 1} 张卡片 back 不能为空")
                        )
                        continue
                    }

                    val tags = cleanTags(note.tags).joinToString(" ")
                    val uri = Uri.parse("content://$CONTENT_PROVIDER_AUTHORITY/notes/")
                    val values = ContentValues().apply {
                        put("mid", modelId)
                        put("did", deck.id)
                        put("flds", "${note.front}\u001f${note.back}")
                        put("tags", tags)
                    }

                    val resultUri = appContext.contentResolver.insert(uri, values)
                    if (resultUri != null) {
                        val noteId = resultUri.lastPathSegment?.toLongOrNull()
                        if (noteId != null && noteId > 0) {
                            succeededIds.add(noteId)
                        } else {
                            errors.add(
                                BatchError(index, AnkiErrors.ADD_NOTE_FAILED, "添加第 ${index + 1} 张卡片失败")
                            )
                        }
                    } else {
                        errors.add(
                            BatchError(index, AnkiErrors.ADD_NOTE_FAILED, "添加第 ${index + 1} 张卡片失败")
                        )
                    }
                } catch (e: Exception) {
                    errors.add(
                        BatchError(
                            index = index,
                            code = AnkiErrors.ADD_NOTE_FAILED,
                            message = e.message ?: "添加失败"
                        )
                    )
                }
            }

            BatchAddResult(
                requested = request.notes.size,
                succeeded = succeededIds.size,
                failed = errors.size,
                noteIds = succeededIds,
                errors = errors
            )
        }

    private fun ensureAvailable() {
        if (!isAnkiDroidInstalled()) throw AnkiDroidNotInstalledException()
        if (!hasPermission()) throw AnkiPermissionDeniedException()
    }

    private fun queryWithTimeout(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        return try {
            appContext.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
        } catch (e: SecurityException) {
            throw AnkiPermissionDeniedException()
        } catch (e: Exception) {
            throw IllegalStateException("AnkiDroid API 不可用: ${e.message}", e)
        }
    }

    private fun cleanTags(tags: List<String>): List<String> {
        return tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    private suspend fun <T> retryOnce(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (e is AnkiDroidNotInstalledException || e is AnkiPermissionDeniedException) throw e
            delay(400)
            block()
        }
    }

    private fun <T> withTimeout(millis: Long, block: () -> T): T = block()
}

class AnkiDroidNotInstalledException : Exception("AnkiDroid 未安装")
class AnkiPermissionDeniedException : Exception("AnkiDroid 权限未授权")
class DeckOperationException(message: String) : Exception(message)
class AddNoteException(message: String) : Exception(message)
