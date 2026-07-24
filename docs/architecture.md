# 架构说明

## 整体架构

```
RakaHub (MCP Client)
    │  Streamable HTTP (JSON-RPC 2.0)
    ▼
AnkiDroid MCP Bridge APK
    │  Foreground Service (Ktor HTTP Server)
    │  127.0.0.1:8766
    │  Bearer Token 鉴权
    │
    ├── /health          (健康检查，不鉴权)
    └── /mcp             (MCP 端点，需鉴权)
         │
         ├── initialize
         ├── ping
         ├── tools/list
         └── tools/call
              │
              ├── bridge_status
              ├── list_decks
              ├── ensure_deck
              ├── add_basic_note
              └── add_basic_notes
                   │
                   ▼
              AnkiDroidRepository
                   │  ContentProvider
                   ▼
              AnkiDroid ContentProvider
              (com.ichi2.anki.flashcards)
```

## 请求和响应链路

1. RakaHub 发送 MCP 请求到 `http://127.0.0.1:8766/mcp`
2. Ktor HTTP Server 接收请求，验证 Bearer Token
3. McpProtocolHandler 解析 JSON-RPC 2.0 请求
4. 路由到对应的 MCP 方法（initialize/ping/tools）
5. 对于 tools/call，ToolRegistry 查找并调用对应工具
6. 工具调用 AnkiRepository 接口
7. AnkiDroidRepository 通过 ContentProvider 与 AnkiDroid 通信
8. 结果以 JSON-RPC 2.0 格式返回

## 为什么不使用无障碍和直接数据库访问

- **无障碍服务**：需要用户手动在系统设置中开启，且每次 Android 版本升级都可能导致 API 变更。AnkiDroid 提供了官方 ContentProvider API，更可靠且无需无障碍权限。
- **直接数据库访问**：AnkiDroid 的 SQLite 数据库结构可能随版本变化，直接读写会导致数据损坏或 AnkiDroid 崩溃。ContentProvider 是 Android 官方推荐的跨应用数据访问方式。

## 组件说明

### McpForegroundService
- Android Foreground Service，保持 HTTP Server 在后台运行
- 显示常驻通知，提供停止按钮
- 服务停止时释放端口和协程资源

### McpProtocolHandler
- 自行实现的 MCP Streamable HTTP 协议子集
- 仅实现本项目需要的 MCP 能力（initialize, ping, tools/list, tools/call）
- 遵循 JSON-RPC 2.0 规范

### AuthInterceptor
- Bearer Token 鉴权
- /health 不鉴权，/mcp 必须鉴权
- 使用恒定时间比较防止时序攻击

### ToolRegistry
- 管理 MCP 工具注册和查找
- 支持 tools/list 和 tools/call 路由

### AnkiRepository
- 接口抽象，隔离 AnkiDroid API 实现
- 生产实现：AnkiDroidRepository（ContentProvider）
- 测试实现：FakeAnkiRepository（内存）

### TokenManager
- SecureRandom 生成 32 字节 Token
- 持久化到 SharedPreferences
- 支持重新生成（旧 Token 立即失效）

### AppConfigRepository
- 端口配置持久化
- 通过 StateFlow 提供响应式更新
