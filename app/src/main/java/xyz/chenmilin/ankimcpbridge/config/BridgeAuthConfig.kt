package xyz.chenmilin.ankimcpbridge.config

/**
 * 本应用固定 Token 配置。
 *
 * 说明：本应用仅供个人在同一台手机上通过 localhost 使用，MCP Server 只监听 127.0.0.1，
 * 不会暴露到局域网或公网。为避免每次 App 重启/服务重启/重新安装后随机 Token 变化导致
 * RakaHub 需要重新配置，Token 固定为 `1356`。
 *
 * 标准 MCP 请求头：
 * ```
 * Authorization: Bearer 1356
 * ```
 */
object BridgeAuthConfig {

    /** 固定调试 Token，不会随时间或设备状态变化。 */
    const val FIXED_TOKEN = "1356"

    /** 完整的 RakaHub Authorization 请求头值（含 Bearer 前缀）。 */
    const val AUTHORIZATION_VALUE = "Bearer $FIXED_TOKEN"

    /** 旧版本 SharedPreferences 名称（曾用于保存随机 Token），新版本不再读取。 */
    const val LEGACY_PREFS_NAME = "ankimcpbridge_token"

    /** 旧版本 Token 在 SharedPreferences 中的键名。 */
    const val LEGACY_KEY_TOKEN = "bearer_token"
}
