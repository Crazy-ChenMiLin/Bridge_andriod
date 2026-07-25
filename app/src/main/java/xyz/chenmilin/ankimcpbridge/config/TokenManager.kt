package xyz.chenmilin.ankimcpbridge.config

import android.content.Context

/**
 * Bearer Token 验证器（v0.2.3 起固定 Token）。
 *
 * 说明：本应用仅供个人在同一台手机上通过 localhost 使用，MCP Server 只监听 127.0.0.1。
 * 为避免随机 Token 在 App 重启、服务重启或重新安装后变化导致 RakaHub 需要重新配置，
 * Token 固定为 [BridgeAuthConfig.FIXED_TOKEN]，不再随机生成、不再持久化、不再刷新。
 */
class TokenManager internal constructor() {

    /** 供 App 生产代码使用的主构造函数。 */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    /** 当前固定调试 Token。 */
    val token: String
        get() = BridgeAuthConfig.FIXED_TOKEN

    /**
     * 恒定时间比较，防止时序攻击。
     *
     * @param provided 调用方提供的 token（会被 trim，但空串仍返回 false）。
     */
    fun verifyToken(provided: String): Boolean {
        return constantTimeEquals(BridgeAuthConfig.FIXED_TOKEN, provided.trim())
    }

    companion object {

        /** 恒定时间比较，防止时序攻击。 */
        fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var result = 0
            for (i in a.indices) {
                result = result or (a[i].code xor b[i].code)
            }
            return result == 0
        }

        /**
         * 迁移清理：清除旧版本随机 Token 的 SharedPreferences 数据。
         *
         * 新版本已不再读取 `ankimcpbridge_token` 中的 `bearer_token`，但为防止旧数据残留，
         * 建议在应用启动时调用一次。
         */
        fun clearLegacyToken(context: Context) {
            context.getSharedPreferences(BridgeAuthConfig.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
    }
}
