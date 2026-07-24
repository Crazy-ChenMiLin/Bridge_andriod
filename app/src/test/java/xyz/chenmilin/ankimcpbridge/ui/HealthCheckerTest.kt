package xyz.chenmilin.ankimcpbridge.ui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HealthCheckerTest {

    @Test
    fun `check captures exception on unreachable port and does not swallow it`() = runTest {
        // 端口 1 不会被本机监听，连接会失败；Result 必须携带异常，
        // 这样 UI 才能展示异常类型与消息（如 IOException / Connection refused）。
        val result = HealthChecker.check(1)
        assertTrue("健康检查失败应返回 Result.failure", result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull("异常不应被吞掉", exception)
        assertFalse("异常消息不应为空", exception!!.message.isNullOrEmpty())
    }
}
