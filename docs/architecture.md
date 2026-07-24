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
- 自行实现的 MCP Streamable HTTP 协议子集（未直接使用官方 MCP Kotlin SDK，
  原因见 `docs/mcp-protocol.md` 的选型说明）
- 仅实现本项目需要的 MCP 能力（initialize, ping, tools/list, tools/call）
- 遵循 JSON-RPC 2.0 规范；支持 `protocolVersion` 协商
- `tools/list` 与 `tools/call` **无需先 initialize**；移除了旧版的全局 `initialized` 可变标志
- 业务错误以工具结果返回（`result.isError = true`），结构性错误返回 JSON-RPC error

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
  - 直接基于**官方 `FlashCardsContract`**（已 vendored 到
    `app/src/main/java/com/ichi2/anki/FlashCardsContract.kt`，去除 `BuildConfig`/`Ease` 依赖，
    `AUTHORITY`/`READ_WRITE_PERMISSION` 硬编码为官方值）。
  - 等价复刻官方 `com.ichi2.anki.api.AddContentApi` 的关键能力：
    `deckList` / `addNewDeck` / `modelList` / `getFieldList` / `addNewBasicModel` /
    `addNote` / `addNotes`。
  - `addNote` 在插入笔记后，将笔记产生的所有卡片移动到目标牌组
    （更新每张 card 的 `Card.DECK_ID`），与官方实现行为一致。
  - **批量添加（`add_basic_notes`）**：通过单次 `ContentResolver.bulkInsert`
    （等价官方 `AddContentApi.addNotes(modelId, deckId, fieldsList, tagsList)`）完成，
    **不再循环调用 `insert`**。`bulkInsert` 仅返回写入行数，故批量结果的
    `noteIdsAvailable=false`；返回结构含 `requested/submitted/succeeded/failed`，
    负值视为整体失败（`BATCH_FAILED`），`succeeded < submitted` 标记 `PARTIAL_FAILURE`。
  - **权限检查**：`hasPermission()` 使用正式的
    `Context.checkPermission(READ_WRITE_DATABASE, Process.myPid(), Process.myUid())`
    （需在 `AndroidManifest.xml` 声明 `<uses-permission android:name="com.ichi2.anki.permission.READ_WRITE_DATABASE" />`）。
  - **初始化重试**：`withAnkiRetry` 仅对“集合尚未初始化”类异常重试一次（延迟 400ms）；
    权限不足 / AnkiDroid 未安装等错误不重试，直接上抛为业务异常。
- 测试实现：FakeAnkiRepository（内存）

### TokenManager
- SecureRandom 生成 32 字节 Token（64 位十六进制）
- 进程内唯一“活跃 token”状态，所有实例共享
- 持久化到 SharedPreferences
- 支持重新生成：内存与持久化同时更新，**旧 Token 在所有实例（含运行中的 MCP Server）中立即失效**

### AppConfigRepository
- 端口配置持久化
- 通过 StateFlow 提供响应式更新
