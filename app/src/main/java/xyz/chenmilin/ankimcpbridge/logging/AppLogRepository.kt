package xyz.chenmilin.ankimcpbridge.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内存日志仓库，存储最近 N 条日志。
 * 不记录 Token 和卡片背面内容。
 */
class AppLogRepository {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    private val lock = Any()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(level: LogLevel, message: String) {
        synchronized(lock) {
            val entry = LogEntry(
                timestamp = System.currentTimeMillis(),
                formattedTime = dateFormat.format(Date()),
                level = level,
                message = message
            )
            val current = _logs.value.toMutableList()
            current.add(entry)
            if (current.size > MAX_LOG_ENTRIES) {
                _logs.value = current.drop(current.size - MAX_LOG_ENTRIES)
            } else {
                _logs.value = current
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            _logs.value = emptyList()
        }
    }

    fun info(message: String) = log(LogLevel.INFO, message)
    fun warn(message: String) = log(LogLevel.WARN, message)
    fun error(message: String) = log(LogLevel.ERROR, message)
    fun debug(message: String) = log(LogLevel.DEBUG, message)

    companion object {
        const val MAX_LOG_ENTRIES = 100
    }
}

data class LogEntry(
    val timestamp: Long,
    val formattedTime: String,
    val level: LogLevel,
    val message: String
)

enum class LogLevel {
    INFO, WARN, ERROR, DEBUG
}
