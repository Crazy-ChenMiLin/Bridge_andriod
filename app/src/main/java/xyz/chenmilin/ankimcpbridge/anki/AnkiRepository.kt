package xyz.chenmilin.ankimcpbridge.anki

interface AnkiRepository {
    fun isAnkiDroidInstalled(): Boolean
    fun hasPermission(): Boolean
    suspend fun listDecks(): List<AnkiDeck>
    suspend fun ensureDeck(name: String): AnkiDeck
    suspend fun addBasicNote(request: AddBasicNoteRequest): AddNoteResult
    suspend fun addBasicNotes(request: AddBasicNotesRequest): BatchAddResult
}
