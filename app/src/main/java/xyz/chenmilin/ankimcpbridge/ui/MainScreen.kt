package xyz.chenmilin.ankimcpbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    var showAddNoteDialog by remember { mutableStateOf(false) }

    // 确认对话框
    if (showAddNoteDialog) {
        AlertDialog(
            onDismissRequest = { showAddNoteDialog = false },
            title = { Text("确认添加测试卡片") },
            text = {
                Text("将在「MCP Test」牌组中添加一张测试卡片。\n\n正面内容包含当前时间，用于区分每次测试。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showAddNoteDialog = false
                    viewModel.testAddNote()
                }) {
                    Text("确认添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddNoteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AnkiDroid MCP Bridge") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── AnkiDroid 状态卡片 ──
            StatusCard(
                title = "AnkiDroid 状态",
                items = listOf(
                    StatusItem(
                        "安装状态",
                        if (state.ankiInstalled) "已安装" else "未安装",
                        if (state.ankiInstalled) StatusColor.OK else StatusColor.ERROR
                    ),
                    StatusItem(
                        "API 权限",
                        if (state.ankiPermissionGranted) "已授权" else "未授权",
                        if (state.ankiPermissionGranted) StatusColor.OK else StatusColor.WARN
                    )
                ),
                action = {
                    Button(
                        onClick = { viewModel.refreshAnkiStatus() },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("刷新")
                    }
                    if (!state.ankiPermissionGranted && state.ankiInstalled) {
                        Button(onClick = { viewModel.requestAnkiPermission() }) {
                            Text("授权 AnkiDroid")
                        }
                    }
                }
            )

            // ── MCP 服务状态卡片 ──
            StatusCard(
                title = "MCP 服务状态",
                items = listOf(
                    StatusItem(
                        "服务状态",
                        if (state.serverRunning) "运行中" else "已停止",
                        if (state.serverRunning) StatusColor.OK else StatusColor.INACTIVE
                    ),
                    StatusItem("主机", state.host, StatusColor.NEUTRAL),
                    StatusItem("端口", state.port.toString(), StatusColor.NEUTRAL),
                    StatusItem(
                        "地址",
                        "http://${state.host}:${state.port}/mcp",
                        StatusColor.NEUTRAL
                    )
                ),
                action = {
                    if (state.serverRunning) {
                        Button(
                            onClick = { viewModel.stopService() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("停止服务")
                        }
                    } else {
                        Button(onClick = { viewModel.startService() }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("启动服务")
                        }
                    }
                }
            )

            // ── Token 卡片 ──
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Bearer Token", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = if (state.tokenVisible) state.token else "●".repeat(state.token.length),
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.weight(1f),
                            visualTransformation = if (state.tokenVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )

                        IconButton(onClick = { viewModel.toggleTokenVisibility() }) {
                            Icon(
                                if (state.tokenVisible) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = "显示/隐藏"
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.copyToClipboard(state.token) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("复制 Token")
                        }
                        OutlinedButton(
                            onClick = { viewModel.regenerateToken() },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("重新生成")
                        }
                    }
                }
            }

            // ── 端口设置 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("端口设置", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = state.portInput,
                            onValueChange = { viewModel.setPortInput(it) },
                            enabled = state.portEditable,
                            modifier = Modifier.width(120.dp),
                            singleLine = true,
                            label = { Text("端口号") }
                        )
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = { viewModel.savePort() },
                            enabled = state.portEditable
                        ) {
                            Text("保存")
                        }
                        if (!state.portEditable) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "服务运行时不可修改",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // ── 测试按钮 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("快速测试", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.testHealthCheck() }) {
                            Text("健康检查")
                        }
                        OutlinedButton(onClick = { viewModel.testListDecks() }) {
                            Text("读取牌组")
                        }
                        OutlinedButton(onClick = { showAddNoteDialog = true }) {
                            Text("添加测试卡片")
                        }
                        OutlinedButton(onClick = { viewModel.copyToClipboard("http://127.0.0.1:${state.port}/mcp") }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("复制地址")
                        }
                    }

                    // 测试结果显示
                    state.testHealthResult?.let {
                        Spacer(Modifier.height(8.dp))
                        TestResultItem("健康检查", it)
                    }
                    state.testDecksResult?.let {
                        Spacer(Modifier.height(4.dp))
                        TestResultItem("读取牌组", it)
                    }
                    state.testAddNoteResult?.let {
                        Spacer(Modifier.height(4.dp))
                        TestResultItem("添加卡片", it)
                    }
                }
            }

            // ── 日志 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("日志", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { viewModel.clearLogs() }) {
                            Text("清空")
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    if (state.logEntries.isEmpty()) {
                        Text(
                            "暂无日志",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            state.logEntries.takeLast(50).forEach { entry ->
                                Text(
                                    text = "[${entry.formattedTime}] [${entry.level}] ${entry.message}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = MaterialTheme.typography.bodySmall.fontSize
                                    ),
                                    color = when (entry.level) {
                                        "ERROR" -> MaterialTheme.colorScheme.error
                                        "WARN" -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(
    title: String,
    items: List<StatusItem>,
    action: @Composable RowScope.() -> Unit = {}
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.label, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = item.color.color.copy(alpha = 0.15f),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(
                                item.value,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = item.color.color
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                action()
            }
        }
    }
}

@Composable
fun TestResultItem(label: String, result: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                result,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }
    }
}

data class StatusItem(
    val label: String,
    val value: String,
    val color: StatusColor
)

enum class StatusColor(val color: androidx.compose.ui.graphics.Color) {
    OK(androidx.compose.ui.graphics.Color(0xFF2E7D32)),
    WARN(androidx.compose.ui.graphics.Color(0xFFE65100)),
    ERROR(androidx.compose.ui.graphics.Color(0xFFC62828)),
    INACTIVE(androidx.compose.ui.graphics.Color(0xFF757575)),
    NEUTRAL(androidx.compose.ui.graphics.Color(0xFF1565C0))
}
