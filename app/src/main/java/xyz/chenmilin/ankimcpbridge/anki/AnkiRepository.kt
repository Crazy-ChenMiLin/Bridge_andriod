package xyz.chenmilin.ankimcpbridge.anki

interface AnkiRepository {
    fun isAnkiDroidInstalled(): Boolean
    fun hasPermission(): Boolean
    suspend fun listDecks(): List<AnkiDeck>
    suspend fun ensureDeck(name: String): AnkiDeck
    suspend fun addBasicNote(request: AddBasicNoteRequest): AddNoteResult
    suspend fun addBasicNotes(request: AddBasicNotesRequest): BatchAddResult

    // ── v0.2.0：通用笔记类型读取与写入 ──
    suspend fun listNoteTypes(): List<AnkiNoteTypeSummary>
    suspend fun getNoteType(noteTypeId: Long): AnkiNoteTypeDetail
    suspend fun addNote(request: AddGenericNoteRequest): AddGenericNoteResult
    suspend fun addNotes(request: AddGenericNotesRequest): BatchAddGenericResult
}
