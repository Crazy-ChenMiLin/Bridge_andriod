package xyz.chenmilin.ankimcpbridge.config

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * 管理 Bearer Token 的生成、持久化与验证。
 *
 * 设计要点（修复 v0.1.0 的 token 失效 bug）：
 * - 进程内唯一的“活跃 token”保存在 companion 的 [activeToken] 中，所有实例共享。
 * - [regenerateToken] 会同时更新内存中的活跃 token 与持久化存储，
 *   因此旧 token 在所有实例（包括已运行的 MCP Server）中立即失效。
 * - [verifyToken] 始终基于内存中的活跃 token 进行恒定时间比较，避免时序攻击。
 */
class TokenManager internal constructor(
    private val persistence: TokenPersistence
) {

    init {
        ensureLoaded()
    }

    /** 供 App 代码使用的主构造函数。 */
    constructor(context: Context) : this(SharedPreferencesTokenPersistence(context))

    /** 当前进程内生效的 token。 */
    val token: String
        get() {
            ensureLoaded()
            return activeToken!!
        }

    /** 重新生成 token，旧 token 立即失效。返回新 token。 */
    fun regenerateToken(): String {
        val newToken = generateToken()
        persistence.save(newToken)
        activeToken = newToken
        return newToken
    }

    /** 恒定时间比较，防止时序攻击。 */
    fun verifyToken(provided: String): Boolean {
        return constantTimeEquals(token, provided)
    }

    private fun ensureLoaded() {
        if (activeToken == null) {
            synchronized(loadLock) {
                if (activeToken == null) {
                    val stored = persistence.get()
                    activeToken = if (!stored.isNullOrBlank()) {
                        stored
                    } else {
                        generateToken().also { persistence.save(it) }
                    }
                }
            }
        }
    }

    companion object {
        internal const val PREFS_NAME = "ankimcpbridge_token"
        internal const val KEY_TOKEN = "bearer_token"
        private const val TOKEN_BYTES = 32

        /** 进程内唯一的活跃 token，所有 TokenManager 实例共享此状态。 */
        @Volatile
        private var activeToken: String? = null
        private val loadLock = Any()

        /** 恒定时间比较，防止时序攻击。 */
        fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var result = 0
            for (i in a.indices) {
                result = result or (a[i].code xor b[i].code)
            }
            return result == 0
        }

        fun generateToken(): String {
            val bytes = ByteArray(TOKEN_BYTES)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** 仅供单元测试：清空进程内 token 状态，避免用例间串扰。 */
        internal fun resetForTest() {
            synchronized(loadLock) { activeToken = null }
        }
    }
}

/** Token 持久化抽象，便于在单元测试中替换为内存实现。 */
internal interface TokenPersistence {
    fun get(): String?
    fun save(token: String)
}

private class SharedPreferencesTokenPersistence(context: Context) : TokenPersistence {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(TokenManager.PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(): String? = prefs.getString(TokenManager.KEY_TOKEN, null)
    override fun save(token: String) {
        prefs.edit().putString(TokenManager.KEY_TOKEN, token).apply()
    }
}

/** 仅供测试的内存持久化实现（不依赖 Android Context）。 */
internal class InMemoryTokenPersistence : TokenPersistence {
    private var value: String? = null
    override fun get(): String? = value
    override fun save(token: String) { value = token }
}
