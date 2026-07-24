package xyz.chenmilin.ankimcpbridge.anki

data class AnkiDeck(
    val id: Long,
    val name: String
)

data class AddBasicNoteRequest(
    val deck: String,
    val front: String,
    val back: String,
    val tags: List<String> = emptyList()
)

data class AddNoteResult(
    val success: Boolean,
    val noteId: Long?,
    val deck: String
)

data class AddBasicNotesRequest(
    val deck: String,
    val notes: List<SingleNoteRequest>
)

data class SingleNoteRequest(
    val front: String,
    val back: String,
    val tags: List<String> = emptyList()
)

data class BatchAddResult(
    val requested: Int,
    val succeeded: Int,
    val failed: Int,
    val noteIds: List<Long>,
    val errors: List<BatchError>
)

data class BatchError(
    val index: Int,
    val code: String,
    val message: String
)
