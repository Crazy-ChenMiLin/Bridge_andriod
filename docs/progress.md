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
- [x] TokenManager (SecureRandom, 32 bytes)
- [x] AppConfigRepository (端口持久化)

### 阶段 3：AnkiDroid 集成
- [x] AnkiDroid 安装检测
- [x] API 权限检测
- [x] FakeAnkiRepository (测试)
- [x] AnkiDroidRepository (ContentProvider)
- [x] AnkiModelResolver (Basic 模型查找)
- [x] 错误处理 (重试一次, 400ms 延迟)

### 阶段 4：MCP 工具
- [x] bridge_status
- [x] list_decks
- [x] ensure_deck
- [x] add_basic_note
- [x] add_basic_notes (批量，最多 100 张)

### 阶段 5：UI
- [x] AnkiDroid 状态显示
- [x] MCP 服务状态显示
- [x] Token 显示/隐藏/复制/重新生成
- [x] 端口设置 (服务停止时可修改)
- [x] 测试按钮 (健康检查、读取牌组、添加卡片)
- [x] 确认对话框 (添加测试卡片)
- [x] 日志区域 (最近 100 条)
- [x] 清空日志

### 阶段 6：测试和文档
- [x] 单元测试 (FakeAnkiRepository, Token, MCP 协议)
- [x] 冒烟测试脚本 (scripts/smoke_test.py)
- [x] GitHub Actions (android.yml + release.yml)
- [x] README.md (中文)
- [x] docs/architecture.md
- [x] docs/mcp-protocol.md
- [x] docs/ankidroid-api.md
- [x] docs/rakahub-setup.md
- [x] docs/troubleshooting.md

## 构建结果

- [x] `./gradlew assembleDebug` → BUILD SUCCESSFUL
- [x] `./gradlew testDebugUnitTest` → BUILD SUCCESSFUL (all tests pass)
- [x] `./gradlew lint` → BUILD SUCCESSFUL

## 待验证（需真机）

- [ ] APK 在真实 Android 手机上安装
- [ ] AnkiDroid ContentProvider API 调用验证
- [ ] RakaHub 连接测试
- [ ] 真实 AnkiDroid 添加卡片验证
- [ ] 前台服务在后台的持久性

## 已知限制

1. AnkiDroid ContentProvider API 的具体行为可能因版本而异
2. 模型创建功能在某些 AnkiDroid 版本中可能受限
3. 批量添加不支持事务，单张失败不影响其他卡片
4. 部分手机厂商的后台管理策略可能影响服务持久性

## 下一步

1. 真机测试 AnkiDroid ContentProvider API
2. 验证 RakaHub 连接
3. 根据测试结果调整 ContentProvider 调用参数
4. 完善 Release 签名配置
