# AnkiDroid MCP Bridge

一个安装在 Android 手机上的本地 MCP Server APK，让 RakaHub 等支持 Streamable HTTP 的 MCP Client 通过本机 localhost 调用 MCP 工具，使用 AnkiDroid ContentProvider API 向 AnkiDroid 添加卡片。

## 这个项目解决什么问题

传统方式在手机上向 AnkiDroid 添加卡片需要：
- 电脑常驻（通过 AnkiConnect）
- Windows/Linux 服务器
- Termux 环境
- ADB 调试

**AnkiDroid MCP Bridge** 实现纯手机方案：
- ✅ 不需要电脑常驻
- ✅ 不需要服务器
- ✅ 不需要 Termux
- ✅ 不需要 ADB
- ✅ 不使用无障碍模拟点击
- ✅ 不直接读写 AnkiDroid 数据库
- ✅ 不在 APK 中调用大模型 API

## 架构图

```
RakaHub (MCP Client)
    │
    │ Streamable HTTP MCP
    │ Authorization: Bearer <token>
    ▼
http://127.0.0.1:8766/mcp
    │
    ▼
AnkiDroid MCP Bridge APK
    │  ┌──────────────────────┐
    │  │  Ktor HTTP Server    │
    │  │  (127.0.0.1:8766)    │
    │  │  ┌────────────────┐  │
    │  │  │ MCP Protocol   │  │
    │  │  │ Handler        │  │
    │  │  │ (JSON-RPC 2.0) │  │
    │  │  └───────┬────────┘  │
    │  │          ▼           │
    │  │  ┌────────────────┐  │
    │  │  │ Tool Registry  │  │
    │  │  │ (5 tools)      │  │
    │  │  └───────┬────────┘  │
    │  └──────────┼───────────┘
    │             ▼
    │  ┌──────────────────────┐
    │  │  AnkiDroidRepository │
    │  │  (ContentProvider)   │
    │  └──────────┬───────────┘
    │             │
    ▼             ▼
AnkiDroid ContentProvider
(com.ichi2.anki.flashcards)
```

## 安装

### 1. 安装 AnkiDroid

从 Google Play 或 [AnkiDroid 官网](https://github.com/ankidroid/Anki-Android/releases) 安装 AnkiDroid。

**已验证版本**: AnkiDroid 2.18+

### 2. 安装本 APK

下载 `app-debug.apk` 或从 GitHub Actions Artifact 下载，在手机上安装。

### 3. 授予 AnkiDroid 权限

1. 打开本 App
2. 点击「授权 AnkiDroid」按钮，将跳转到 AnkiDroid
3. 在 AnkiDroid 中：设置 → 高级 → 允许 API 访问
4. 返回本 App，点击「刷新」确认权限状态

### 4. 启动 MCP 服务

1. 在本 App 首页点击「启动服务」
2. 服务将在 `127.0.0.1:8766` 上运行
3. 状态栏会显示常驻通知

## 在 RakaHub 中配置

| 配置项 | 值 |
|--------|-----|
| 名称 | AnkiDroid MCP |
| 传输类型 | Streamable HTTP |
| 服务器地址 | `http://127.0.0.1:8766/mcp` |

**自定义请求头**：

| 名称 | 内容 |
|------|------|
| Authorization | `Bearer 你的Token` |

Token 在 App 首页的「Bearer Token」卡片中查看和复制。

## 测试提示词

### 提示词 1：状态检查

```
调用 bridge_status，告诉我 AnkiDroid 和 MCP 服务是否正常，不要修改任何卡片。
```

### 提示词 2：读取牌组

```
读取我的 AnkiDroid 牌组列表，不要创建或修改任何内容。
```

### 提示词 3：批量制卡

```
把下面内容拆分成 5 张简洁的问答卡，调用 add_basic_notes，一次性写入「Java面试」牌组。
添加前先向我展示卡片内容，得到确认后再写入。

[你的内容]
```

## MCP 工具

| 工具名 | 功能 |
|--------|------|
| `bridge_status` | 获取服务状态和 AnkiDroid 连接状态 |
| `list_decks` | 列出所有牌组 |
| `ensure_deck` | 确保牌组存在（自动创建） |
| `add_basic_note` | 添加单张 Basic 卡片 |
| `add_basic_notes` | 批量添加 Basic 卡片（最多 100 张） |

## 故障排查

### Connection refused
- 确认已点击「启动服务」
- 检查端口是否正确（默认 8766）
- 查看通知栏是否有「Anki MCP 服务正在运行」

### 401 Unauthorized
- 确认已复制正确的 Token
- Token 是否过期（重新生成后旧 Token 立即失效）
- 请求头格式：`Authorization: Bearer <token>`

### AnkiDroid 未安装
- 从 Google Play 安装 AnkiDroid
- 确认包名为 `com.ichi2.anki`

### 权限不足
- 在 AnkiDroid 设置中启用 API 访问
- 返回本 App 点击「刷新」

### 端口被占用
- 在 App 中修改端口号（服务停止时可修改）
- 检查是否有其他应用占用该端口

### RakaHub 无法访问 HTTP
- RakaHub 必须运行在同一台手机上
- 确认地址为 `http://127.0.0.1:8766/mcp`（不是 `https`）

### App 进入后台后服务停止
- 前台服务通过常驻通知保持运行
- 在系统设置中关闭电池优化对本 App 的限制
- 不同手机品牌的后台管理策略不同，详见下文

### 手机系统杀后台
- **华为/荣耀**：设置 → 应用 → 应用启动管理 → 手动管理（允许自启动、关联启动、后台活动）
- **小米/Redmi**：设置 → 应用设置 → 授权管理 → 自启动管理 → 允许
- **OPPO/一加**：设置 → 电池 → 应用耗电管理 → 不优化
- **vivo/iQOO**：设置 → 电池 → 后台高耗电 → 允许
- **三星**：设置 → 设备维护 → 电池 → 不监控的应用 → 添加
- **原生 Android**：设置 → 应用 → 电池 → 不限制

### 牌组创建失败
- 确认 AnkiDroid API 权限已授予
- 牌组名称不要超过 200 字符

### 找不到 Basic 模型
- 在 AnkiDroid 中确认存在包含 Front 和 Back 字段的基础笔记类型
- 或让 App 自动创建 "MCP Basic" 模型

## 安全说明

- ⚠️ 服务**仅监听 localhost (127.0.0.1)**，不可从其他设备访问
- ⚠️ **不要**修改为 `0.0.0.0`
- ⚠️ **不要**将端口暴露到公网
- ⚠️ **不要把 Token 发给别人**
- ⚠️ 本服务**仅用于本机 localhost 通信**，不实现 TLS
- ⚠️ Token 存储在应用私有目录中

## 构建

```bash
# 设置 Android SDK
export ANDROID_HOME=/path/to/android/sdk

# 构建 Debug APK
./gradlew assembleDebug

# 运行测试
./gradlew testDebugUnitTest

# 运行 Lint
./gradlew lint

# APK 输出路径
# app/build/outputs/apk/debug/app-debug.apk
```

## 技术栈

- Kotlin + Gradle Kotlin DSL
- Jetpack Compose (Material 3)
- Ktor Server (Netty)
- Kotlin Coroutines
- Android Foreground Service
- AnkiDroid ContentProvider API
- JUnit 4

## 许可证

Apache-2.0
