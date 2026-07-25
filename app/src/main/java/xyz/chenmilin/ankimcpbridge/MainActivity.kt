package xyz.chenmilin.ankimcpbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import xyz.chenmilin.ankimcpbridge.anki.AnkiDroidRepository
import xyz.chenmilin.ankimcpbridge.config.TokenManager
import xyz.chenmilin.ankimcpbridge.ui.MainScreen
import xyz.chenmilin.ankimcpbridge.ui.MainViewModel
import xyz.chenmilin.ankimcpbridge.ui.theme.AnkiMCPBridgeTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.onPermissionResult(AnkiDroidRepository.READ_WRITE_PERMISSION, isGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 清除旧版本随机 Token 数据（v0.2.3 起已改用固定 Token）。
        TokenManager.clearLegacyToken(this)

        // 监听 ViewModel 的权限申请事件，真正拉起系统权限弹窗
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.permissionRequest.collect { permission ->
                    permissionLauncher.launch(permission)
                }
            }
        }

        setContent {
            AnkiMCPBridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从权限弹窗返回后刷新授权状态
        viewModel.refreshAnkiStatus()
    }
}
