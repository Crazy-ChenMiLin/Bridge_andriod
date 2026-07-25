package xyz.chenmilin.ankimcpbridge.ui

/**
 * 把原始 Token 拼成标准的 Authorization 请求头值，例如 `Bearer 1356`。
 *
 * 设计为纯函数，便于单元测试，且把“拼 Token”的易错点集中在一处，避免：
 * - 漏掉 Bearer 与 Token 之间的英文空格；
 * - 误用横杠（如 `Bearer-Token`）；
 * - 与上游已带的前缀重复，拼成 `Bearer Bearer Token`。
 *
 * @param token 原始 Token（内部保存的就是这种原始值）
 * @return 形如 `Bearer abc123` 的完整值；Token 为空或清理后为空时返回 null。
 */
internal fun buildAuthorizationValue(token: String): String? {
    val normalized = token.trim()
    if (normalized.isBlank()) return null

    // 若调用方/上游意外在 Token 里带了 "Bearer " 前缀，去掉后再拼，避免重复。
    // 仅出现 "Bearer" 字样但后面没有实际 Token 时，视为空。
    val rawToken = if (normalized.startsWith("Bearer ", ignoreCase = true)) {
        normalized.substring(7).trim()
    } else if (normalized.equals("Bearer", ignoreCase = true)) {
        return null
    } else {
        normalized
    }
    if (rawToken.isBlank()) return null

    return "Bearer $rawToken"
}
