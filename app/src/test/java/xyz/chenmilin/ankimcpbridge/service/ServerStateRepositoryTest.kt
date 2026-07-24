package xyz.chenmilin.ankimcpbridge.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ServerStateRepositoryTest {

    @Test
    fun `running defaults to false before any server start`() = runBlocking {
        // 未启动服务时不应错误地显示为“运行中”
        assertFalse(ServerStateRepository.isRunning.value)
    }
}
