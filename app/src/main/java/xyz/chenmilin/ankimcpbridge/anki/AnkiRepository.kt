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

    // PC Anki MCP compatible capabilities available through AnkiDroid ContentProvider.
    suspend fun findNotes(query: String): List<Long>
    suspend fun notesInfo(noteIds: List<Long>): List<AnkiNoteInfo>
    suspend fun findDuplicateNotes(noteTypeId: Long, firstFieldValue: String): List<Long>
    suspend fun updateNoteFields(noteId: Long, fields: Map<String, String>): Boolean
    suspend fun getTags(pattern: String? = null): List<String>
    suspend fun addTags(noteIds: List<Long>, tags: List<String>): Int
    suspend fun removeTags(noteIds: List<Long>, tags: List<String>): Int
}
