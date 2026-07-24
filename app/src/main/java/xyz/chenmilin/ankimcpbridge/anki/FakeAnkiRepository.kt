package xyz.chenmilin.ankimcpbridge.anki

/**
 * 测试用 AnkiRepository 实现。
 * 使用内存数据结构模拟 AnkiDroid API 行为。
 */
class FakeAnkiRepository : AnkiRepository {

    private val decks = mutableListOf<AnkiDeck>()
    private val notes = mutableListOf<FakeNote>()
    private var nextDeckId: Long = 1
    private var nextNoteId: Long = 1
    private var installed = true
    private var permissionGranted = true

    fun setInstalled(value: Boolean) { installed = value }
    fun setPermissionGranted(value: Boolean) { permissionGranted = value }

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
                front = request.front,
                back = request.back,
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
            notes.add(FakeNote(id = noteId, deckId = deck.id, front = note.front, back = note.back, tags = note.tags))
            succeededIds.add(noteId)
        }

        return BatchAddResult(
            requested = request.notes.size,
            succeeded = succeededIds.size,
            failed = errors.size,
            noteIds = succeededIds,
            errors = errors
        )
    }

    data class FakeNote(
        val id: Long,
        val deckId: Long,
        val front: String,
        val back: String,
        val tags: List<String>
    )
}
