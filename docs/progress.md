# 开发进度

## 已完成

### 阶段 1：工程骨架
- [x] Android 工程结构 (Gradle Kotlin DSL)
- [x] Compose 首页骨架 (MainScreen + MainViewModel)
- [x] Foreground Service (McpForegroundService)
- [x] GET /health 端点
- [x] Ktor Netty HTTP Server

### 阶段 2：MCP 协议
- [x] MCP initialize
- [x] notifications/initialized
- [x] ping
- [x] tools/list
- [x] tools/call
- [x] bridge_status 工具
- [x] Bearer Token 鉴权 (AuthInterceptor)
- [x] TokenManager（固定本机 Token 1356）
- [x] AppConfigRepository (端口持久化)

### 阶段 3：AnkiDroid 集成
- [x] AnkiDroid 安装检测
- [x] API 权限检测
- [x] FakeAnkiRepository (测试)
- [x] AnkiDroidRepository（直接基于官方 `FlashCardsContract`，等价复刻 `AddContentApi`）
- [x] AnkiModelResolver（按 `field_names` JSON 解析查找 Basic 模型，并支持创建 "MCP Basic"）
- [x] 无虚假重试/超时封装，异常直接透传给上层处理

### 阶段 4：MCP 工具
- [x] bridge_status
- [x] list_decks
- [x] ensure_deck
- [x] add_basic_note
- [x] add_basic_notes (批量，最多 100 张)

### 阶段 5：UI
- [x] AnkiDroid 状态显示
- [x] MCP 服务状态显示
- [x] Token 显示/复制（固定显示 1356，无刷新入口）
- [x] 端口设置 (服务停止时可修改)
- [x] 测试按钮 (健康检查、读取牌组、添加卡片)
- [x] 确认对话框 (添加测试卡片)
- [x] 日志区域 (最近 100 条)
- [x] 清空日志

### 阶段 6：测试和文档
- [x] 单元测试 (FakeAnkiRepository, Token, MCP 协议)
- [x] 冒烟测试脚本 (scripts/smoke_test.py)
- [x] GitHub Actions (ci.yml + release.yml)
- [x] README.md (中文)
- [x] docs/architecture.md
- [x] docs/mcp-protocol.md
- [x] docs/ankidroid-api.md
- [x] docs/rakahub-setup.md
- [x] docs/troubleshooting.md
- [x] docs/manual-test-checklist.md（手动测试清单）

## v0.1.1 修复（Phase 2–8）

针对 v0.1.0 的已知缺陷进行了修复，并补充测试与文档：

- [x] **TokenManager**：v0.1.1 曾将 token 状态改为进程内唯一并补充测试；v0.2.3 起已改为固定本机 Token `1356`，不再生成、持久化或刷新动态 Token。
- [x] **前台服务生命周期**：`onDestroy` 先同步停止 HTTP Server 再取消协程作用域，避免 Netty 泄漏；移除 `FakeAnkiRepositoryWrapper` 回退实现。
- [x] **线程**：删除虚假的 `withTimeout` 封装，直接调用 `ContentResolver` 查询。
- [x] **日志**：`AppLogRepository` 改为进程级单例（`.instance`），UI 与服务共享同一份日志。
- [x] **MCP 协议层**：移除全局 `initialized` 可变标志，tools/list 与 tools/call 无需先 initialize；支持 `protocolVersion` 协商；业务错误以工具结果返回（`result.isError = true`），结构性错误返回 JSON-RPC error；记录未采用官方 MCP Kotlin SDK 的选型理由。
- [x] **AnkiDroidRepository**：基于 vendored 官方 `FlashCardsContract` 重写，使用正确列名/URI（`Deck.DECK_ID`/`DECK_NAME`、`Note.MID`/`FLDS`/`TAGS`、`Model.FIELD_NAMES` 等），等价 `deckList`/`addNewDeck`/`modelList`/`getFieldList`/`addNewBasicModel`/`addNote`/`addNotes`；`addNote` 插入后将卡片移动到目标牌组。
- [x] **AnkiModelResolver**：改用 `Model.FIELD_NAMES`（JSON 数组）解析字段，修正此前误用 `flds` 列的问题。
- [x] **批量错误索引**：`add_basic_notes` 错误 `index` 映射回原始请求下标。
- [x] **Manifest**：`allowBackup=false`，`specialUse` 前台服务补充 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 子类型声明。
- [x] **GitHub Actions**：拆分为 `ci.yml`（PR/workflow_dispatch，`abortOnError=true`）与 `release.yml`（`v*` tag 触发，GitHub Release + SHA256SUMS.txt + 可选签名）。
- [x] 版本号提升至 `0.1.1`（`versionCode=2`）。

### v0.1.1 补充修复（Spec 复核）

针对原始修复中尚未完全覆盖的规格项，进一步落实：

- [x] **批量添加真批量语义**：`add_basic_notes` 改用单次 `ContentResolver.bulkInsert`
  （等价官方 `AddContentApi.addNotes(modelId, deckId, fieldsList, tagsList)`），**不再循环调用
  `insert`**。`BatchAddResult` 新增 `submitted` 与 `noteIdsAvailable` 字段（批量路径恒为 `false`）；
  `bulkInsert` 返回负值视为整体失败（`BATCH_FAILED`），`succeeded < submitted` 标记 `PARTIAL_FAILURE`。
- [x] **权限检查**：`hasPermission()` 改用正式的 `Context.checkPermission(READ_WRITE_DATABASE,
  Process.myPid(), Process.myUid())`，并在 `AndroidManifest.xml` 声明对应 `<uses-permission>`。
- [x] **初始化重试**：新增 `withAnkiRetry`，仅对“集合尚未初始化”类异常重试一次（延迟 400ms）；
  权限不足 / AnkiDroid 未安装等错误**不重试**，直接上抛为业务异常。
- [x] **bridge_status 版本字段**：新增 `appVersion`（`BuildConfig.VERSION_NAME`）与
  `apiHostSpecVersion`（宿主 API 规格版本，当前 `1.0.0`）。
- [x] **测试脚本**：新增 `scripts/add_test_note.py`，默认仅预览，**必须 `--confirm` 才真正写入**
  一张测试卡片（避免误写）。
- [x] **Release 流程**：产出文件名改为 `AnkiDroid-MCP-Bridge-<TAG>-debug.apk` /
  `-release.apk`；签名密钥改用 `ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` /
  `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` 四个 Secrets；checkout `fetch-depth: 0`。
- [x] **标签驱动版本号**：`app/build.gradle.kts` 读取 `GITHUB_REF_NAME`（`v0.1.1` →
  `versionName="0.1.1"`、`versionCode=101`），无需手动改版本号。
- [x] **MCP Kotlin SDK 选型证据**：实际将 `kotlin-sdk-jvm:0.14.0` 加入 Android 模块复现编译失败
  （Kotlin 元数据 2.1+/2.3.0 与本项目 1.9.22 工具链不兼容），详细证据见
  `docs/mcp-kotlin-sdk-evaluation.md`。

## 构建结果

- [x] `./gradlew assembleDebug` → BUILD SUCCESSFUL
- [x] `./gradlew testDebugUnitTest` → BUILD SUCCESSFUL（全部用例通过）
- [x] `./gradlew lint` → BUILD SUCCESSFUL（`abortOnError=true`）

## 待验证（需真机）

- [ ] 参照 `docs/manual-test-checklist.md` 在真实 Android 手机上联调
- [ ] AnkiDroid ContentProvider API 调用验证（含 `bulkInsert` 批量写入）
- [ ] 真机验证 `hasPermission()` 的 `checkPermission` 语义与授权流程
- [ ] 真机验证“集合未初始化”场景下的重试一次是否生效
- [ ] RakaHub 连接测试
- [ ] 真实 AnkiDroid 添加卡片验证（单张 + 批量）
- [ ] 前台服务在后台的持久性

## 已知限制

1. AnkiDroid ContentProvider API 的具体行为可能因版本而异
2. 模型创建功能在某些 AnkiDroid 版本中可能受限
3. 批量添加：因 `bulkInsert` 不返回 noteId，`noteIdsAvailable=false`；卡片 best-effort 移动到
   目标牌组，单张失败不影响其他卡片（错误以原始 `index` 回传）
4. 部分手机厂商的后台管理策略可能影响服务持久性

## 下一步

1. 真机测试 AnkiDroid ContentProvider API（见 `docs/manual-test-checklist.md`）
2. 验证 RakaHub 连接
3. 根据测试结果调整 ContentProvider 调用参数
4. 完善 Release 签名配置（在仓库 Secrets 配置 `ANDROID_KEYSTORE_*` 四个密钥）
