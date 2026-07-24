# MCP Kotlin SDK 选型评估（为何未直接使用官方 SDK）

本文件记录对官方 `io.modelcontextprotocol:kotlin-sdk` 的评估结论与**可复现的具体证据**，
说明本项目为何自行实现 MCP 必需的最小协议子集，而非直接引入该 SDK。

## 结论

**官方 MCP Kotlin SDK 无法在本项目的 Android 构建中通过编译。** 将其加入 Android
`app` 模块后，`compileDebugKotlin` 直接失败，报“不兼容的 Kotlin 元数据版本”。
即使强行升级 Kotlin 工具链到 2.x 以绕过元数据检查，也会引入 Ktor 3.4.3、kotlin-stdlib 2.3.x
等与本项目 Compose/AndroidX 验证版本冲突的依赖，并显著增大方法数与包体积。

此外，SDK 的 Streamable HTTP 客户端传输基于 **SSE（Server-Sent Events / `text/event-stream`）**，
与本项目“纯 JSON 请求/响应、仅 localhost、不使用 SSE/WebSocket”的硬性要求（规格 §3/§5）相悖。

因此保留自实现方案（JSON-RPC 2.0 + Streamable HTTP 的最小子集）。

## 证据一：官方 SDK 无 Android/ART 目标

官方仓库自述（<https://github.com/modelcontextprotocol/kotlin-sdk>）：

> Kotlin Multiplatform SDK for the Model Context Protocol. It enables Kotlin applications
> targeting **JVM, Native, JS, and Wasm** to ...

即发布目标为 **JVM / Native / JS / Wasm**，**没有 Android（ART）目标**。在 Android 模块中只能引用其
`jvm` 变体（`io.modelcontextprotocol:kotlin-sdk-jvm`），依赖 JVM→Android 的兼容垫片，而非原生 Android 目标。

## 证据二：引入后编译直接失败（可复现）

在 `app/build.gradle.kts` 的 `dependencies` 中临时加入：

```kotlin
implementation("io.modelcontextprotocol:kotlin-sdk-jvm:0.14.0")   // 0.14.0 为 Maven Central 当时最新版本
```

执行 `./gradlew :app:compileDebugKotlin`，输出（节选，已脱敏路径）：

```
e: Incompatible classes were found in dependencies. Remove them from the classpath or use '-Xskip-metadata-version-check' to suppress errors
e: .../io.modelcontextprotocol/kotlin-sdk-jvm/0.14.0/kotlin-sdk-jvm-0.14.0.jar!/META-INF/kotlin-sdk.kotlin_module
   Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.1.0, expected version is 1.9.0.
e: .../io.modelcontextprotocol/kotlin-sdk-client-jvm/0.14.0/kotlin-sdk-client-jvm-0.14.0.jar!/META-INF/kotlin-sdk-client.kotlin_module
   Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.1.0, expected version is 1.9.0.
e: .../org.jetbrains.kotlin/kotlin-stdlib/2.3.21/kotlin-stdlib-2.3.21.jar!/META-INF/kotlin-stdlib.kotlin_module
   Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.3.0, expected version is 1.9.0.
e: .../io.ktor/ktor-client-core-jvm/3.4.3/ktor-client-core-jvm-3.4.3.jar!/META-INF/ktor-client-core.kotlin_module
   Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.3.0, expected version is 1.9.0.
e: .../io.ktor/ktor-server-sse-jvm/3.4.3/ktor-server-sse-jvm-3.4.3.jar!/META-INF/ktor-server-sse.kotlin_module
   Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.3.0, expected version is 1.9.0.
e: .../io.ktor/ktor-websockets-jvm/3.4.3/ktor-websockets-jvm-3.4.3.jar!/META-INF/ktor-websockets.kotlin_module
   Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.3.0, expected version is 1.9.0.
e: .../org.jetbrains.kotlinx/kotlinx-coroutines-android/1.11.0/kotlinx-coroutines-android-1.11.0.jar!/META-INF/kotlinx-coroutines-android.kotlin_module
   Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.2.0, expected version is 1.9.0.
e: .../org.jetbrains.kotlinx/kotlinx-collections-immutable-jvm/0.5.0/...kotlin_module
   Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.3.0, expected version is 1.9.0.
```

### 根因

- 官方 SDK 的 `jvm` 变体由 **Kotlin 2.1+** 编译（元数据版本 2.1.0/2.3.0）。
- 它强制拉入 **Ktor 3.4.3**（客户端 + 服务端 websockets/sse）、**kotlin-stdlib 2.3.x**、
  **kotlinx-coroutines 1.11.0**、**kotlinx-collections-immutable 0.5.0** 等。
- 本项目的 Kotlin 工具链为 **1.9.22**（元数据期望版本 1.9.0），与 SDK 依赖的 2.x 元数据不兼容，
  导致 `compileDebugKotlin` 直接失败。

即便用 `-Xskip-metadata-version-check` 跳过检查，或把 Kotlin 插件升级到 2.x，也会引入与
Compose/AndroidX 验证版本不一致的 Ktor 3.x 与 kotlin-stdlib 2.3.x，并显著增大方法与包体积，
对“仅暴露 5 个本地工具”的轻量桥接而言性价比极低。

## 证据三：传输方式不匹配（架构层面）

官方 SDK 的 Streamable HTTP 客户端传输依赖 SSE（`io.ktor:ktor-sse` / `ktor-server-sse` 已随依赖树被拉入），
即服务端→客户端通过 `text/event-stream` 推送。本项目要求**纯 JSON 请求/响应、仅 127.0.0.1、
不使用 SSE/WebSocket**（规格 §3/§5）。即便前两条被绕过，传输模型本身也不符合要求。

## 决策

- 继续**自行实现** MCP 最小协议子集（JSON-RPC 2.0 + Streamable HTTP 的 `/mcp` 端点）。
- 据此移除了旧版本“必须先 `initialize` 才能调用工具”的全局可变状态（`initialized` 标志）。
- 5 个本地工具（bridge_status / list_decks / ensure_deck / add_basic_note / add_basic_notes）
  由本项目直接实现，依赖更少、更易审计、包体积更小。
