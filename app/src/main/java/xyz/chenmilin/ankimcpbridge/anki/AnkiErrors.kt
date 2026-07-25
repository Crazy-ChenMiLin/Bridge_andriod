package xyz.chenmilin.ankimcpbridge.anki

object AnkiErrors {
    const val ANKIDROID_NOT_INSTALLED = "ANKIDROID_NOT_INSTALLED"
    const val ANKI_PERMISSION_DENIED = "ANKI_PERMISSION_DENIED"
    const val ANKI_API_UNAVAILABLE = "ANKI_API_UNAVAILABLE"
    const val MODEL_NOT_FOUND = "MODEL_NOT_FOUND"
    const val DECK_OPERATION_FAILED = "DECK_OPERATION_FAILED"
    const val ADD_NOTE_FAILED = "ADD_NOTE_FAILED"
    const val PARTIAL_FAILURE = "PARTIAL_FAILURE"
    const val BATCH_FAILED = "BATCH_FAILED"
    const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
    const val INVALID_FRONT = "INVALID_FRONT"
    const val INVALID_BACK = "INVALID_BACK"
    const val BATCH_TOO_LARGE = "BATCH_TOO_LARGE"

    // ── v0.2.0 通用笔记类型 ──
    const val NOTE_TYPE_NOT_FOUND = "NOTE_TYPE_NOT_FOUND"
    const val FIELD_NOT_FOUND = "FIELD_NOT_FOUND"
    const val NO_VALID_FIELD = "NO_VALID_FIELD"
    const val AMBIGUOUS_FIELD = "AMBIGUOUS_FIELD"
    const val INVALID_NOTE_TYPE_ID = "INVALID_NOTE_TYPE_ID"
    const val PERSISTENCE_CHECK_FAILED = "PERSISTENCE_CHECK_FAILED"

    // ── 规格明确错误码（与上面已有常量对齐，便于客户端识别） ──
    /** 字段歧义：忽略大小写后一个字段匹配到多个输入键（对应 [AMBIGUOUS_FIELD]）。 */
    const val NOTE_TYPE_AMBIGUOUS = AMBIGUOUS_FIELD
    /** 输入中出现未知字段（对应 [FIELD_NOT_FOUND]）。 */
    const val UNKNOWN_FIELD = FIELD_NOT_FOUND
    /** 所有字段均为空（对应 [NO_VALID_FIELD]）。 */
    const val ALL_FIELDS_EMPTY = NO_VALID_FIELD
    /** 卡片模板读取不受当前 AnkiDroid 版本/接口支持（best-effort 失败时的提示码）。 */
    const val NOTE_TEMPLATE_READ_UNSUPPORTED = "NOTE_TEMPLATE_READ_UNSUPPORTED"
    /** 预置笔记类型自动创建不受公开 API 支持（须手动在 AnkiDroid 创建）。 */
    const val PRESET_CREATION_UNSUPPORTED = "PRESET_CREATION_UNSUPPORTED"
}
