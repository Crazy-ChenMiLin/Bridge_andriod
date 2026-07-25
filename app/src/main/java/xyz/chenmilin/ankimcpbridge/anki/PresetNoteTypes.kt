package xyz.chenmilin.ankimcpbridge.anki

/**
 * v0.2.0 预置笔记类型定义。
 *
 * 重要边界：本项目仅依赖本地 [com.ichi2.anki.FlashCardsContract] 副本，其公开 API 支持
 * 创建“带自定义字段的笔记类型”（通过 `Model.CONTENT_URI` 写入 `NAME` + `FIELD_NAMES`），
 * **但不支持**创建自定义卡片模板（正面/背面 HTML）或 CSS。因此本文件仅保留预置定义，
 * 供 README 给出手动创建说明；Bridge 不通过私有数据库绕过该限制，也不会自动创建模板。
 *
 * 通用 [add_note] / [add_notes] 始终可以使用用户在 AnkiDroid 中**自行创建**的任意笔记类型。
 */
object PresetNoteTypes {

    /** 预置键：供将来若公开 API 支持模板创建时使用，也用于 README 文档索引。 */
    enum class Preset(val key: String, val displayName: String) {
        GENERAL("general", "MCP 通用问答"),
        INTERVIEW("interview", "MCP 面试题"),
        ALGORITHM("algorithm", "MCP 算法题"),
        TROUBLESHOOTING("troubleshooting", "MCP 错误排查")
    }

    /**
     * 单个预置笔记类型定义。
     * @param preset 预置键
     * @param name 建议的笔记类型名称（用户在 AnkiDroid 中创建时使用）
     * @param fields 有序字段名（写入时必须按此顺序映射）
     * @param frontTemplate 建议的正面模板（AnkiDroid 模板语法，仅作文档/手动创建参考）
     * @param backTemplate 建议的背面模板
     */
    data class Definition(
        val preset: Preset,
        val name: String,
        val fields: List<String>,
        val frontTemplate: String,
        val backTemplate: String
    )

    val GENERAL = Definition(
        preset = Preset.GENERAL,
        name = "MCP 通用问答",
        fields = listOf("问题", "答案", "补充", "来源"),
        frontTemplate = "{{问题}}",
        backTemplate = "{{FrontSide}}<hr id=answer>{{答案}}{{#补充}}<br><br><b>补充：</b>{{补充}}{{/补充}}{{#来源}}<br><br><i>来源：{{来源}}</i>{{/来源}}"
    )

    val INTERVIEW = Definition(
        preset = Preset.INTERVIEW,
        name = "MCP 面试题",
        fields = listOf("问题", "简答", "详细回答", "案例", "追问", "来源"),
        frontTemplate = "{{问题}}",
        backTemplate = "{{FrontSide}}<hr id=answer><b>简答：</b>{{简答}}<br><br><b>详细回答：</b>{{详细回答}}{{#案例}}<br><br><b>案例：</b>{{案例}}{{/案例}}{{#追问}}<br><br><b>追问：</b>{{追问}}{{/追问}}{{#来源}}<br><br><i>来源：{{来源}}</i>{{/来源}}"
    )

    val ALGORITHM = Definition(
        preset = Preset.ALGORITHM,
        name = "MCP 算法题",
        fields = listOf("题目", "核心思路", "复杂度", "Java代码", "易错点", "来源"),
        frontTemplate = "{{题目}}",
        // 代码区使用 <pre><code>，不依赖任何外部 JS 库。
        backTemplate = "{{FrontSide}}<hr id=answer><b>核心思路：</b>{{核心思路}}<br><br><b>复杂度：</b>{{复杂度}}<br><br><b>代码：</b><br><pre><code>{{Java代码}}</code></pre>{{#易错点}}<br><br><b>易错点：</b>{{易错点}}{{/易错点}}{{#来源}}<br><br><i>来源：{{来源}}</i>{{/来源}}"
    )

    val TROUBLESHOOTING = Definition(
        preset = Preset.TROUBLESHOOTING,
        name = "MCP 错误排查",
        fields = listOf("现象", "根因", "排查过程", "解决方案", "预防"),
        frontTemplate = "{{现象}}",
        backTemplate = "{{FrontSide}}<hr id=answer><b>根因：</b>{{根因}}<br><br><b>排查过程：</b>{{排查过程}}<br><br><b>解决方案：</b>{{解决方案}}{{#预防}}<br><br><b>预防：</b>{{预防}}{{/预防}}"
    )

    /** 全部预置定义（按推荐顺序）。 */
    val ALL: List<Definition> = listOf(GENERAL, INTERVIEW, ALGORITHM, TROUBLESHOOTING)

    /** 按预置键查找定义。 */
    fun byKey(key: String): Definition? = ALL.firstOrNull { it.preset.key.equals(key, ignoreCase = true) }
}
