# MCP 协议说明

## 传输方式

- **协议**: MCP Streamable HTTP
- **传输**: HTTP POST
- **端点**: `/mcp`
- **数据格式**: JSON-RPC 2.0
- **Content-Type**: `application/json`
- **鉴权**: `Authorization: Bearer <token>`

## 支持的 MCP 方法

| 方法 | 类型 | 说明 |
|------|------|------|
| `initialize` | 请求 | 初始化 MCP 会话 |
| `notifications/initialized` | 通知 | 客户端确认初始化完成 |
| `ping` | 请求 | 心跳检测 |
| `tools/list` | 请求 | 列出可用工具 |
| `tools/call` | 请求 | 调用工具 |

## 协议版本

`2024-11-05`（服务端支持协商：若客户端声明其它版本，服务端回协商后的受支持版本）。

## 关于 MCP Kotlin SDK 的选型说明

本项目**未直接使用**官方 `io.modelcontextprotocol:kotlin-sdk`，而是自行实现了 MCP
必需的最小协议子集（JSON-RPC 2.0 + Streamable HTTP）。原因与**可复现证据**见
[`docs/mcp-kotlin-sdk-evaluation.md`](./mcp-kotlin-sdk-evaluation.md)，要点：

1. **编译不兼容**：将 `kotlin-sdk-jvm:0.14.0` 加入 Android 模块后，`compileDebugKotlin`
   直接失败——SDK 及其拉入的 Ktor 3.4.3、kotlin-stdlib 2.3.x 由 Kotlin 2.1+/2.3 编译，
   元数据版本（2.1.0/2.3.0）高于本项目 Kotlin 1.9.22 工具链期望的 1.9.0。
2. **传输不匹配**：SDK 的 Streamable HTTP 客户端传输基于 **SSE（Server-Sent Events）**，
   违反本项目“纯 JSON、仅 localhost、不使用 SSE/WebSocket”的硬性要求（规格 §3/§5）。
3. **代价过高**：即便强行升级 Kotlin 工具链绕过元数据检查，也会引入与 Compose/AndroidX
   验证版本冲突的 Ktor 3.x 与 kotlin-stdlib 2.3.x，并显著增大方法与包体积。

因此保留自实现方案，并据此移除了旧版本中“必须 `initialize` 后才能调用工具”的
全局可变状态（全局 `initialized` 标志），符合规格要求。

## 业务错误的返回方式

业务错误（AnkiDroid 未安装、权限不足、笔记类型缺失、添加失败、字段校验不通过等）
**不以 JSON-RPC error 返回**，而是作为工具调用结果返回，并设置 `result.isError = true`：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      { "type": "text", "text": "{\"code\":\"ANKIDROID_NOT_INSTALLED\",\"message\":\"...\"}" }
    ],
    "isError": true
  }
}
```

仅当请求本身结构性不合法（缺少必填参数、未知方法、JSON 解析失败等）时，才返回
JSON-RPC error（见下表）。批量添加中部分失败时，整体结果 `isError = true`，
并在 `errors` 数组中携带每条失败对应的**原始请求下标**（`index`）。

## JSON-RPC 2.0 错误码

| 错误码 | 说明 |
|--------|------|
| -32700 | Parse error |
| -32600 | Invalid Request |
| -32601 | Method not found |
| -32602 | Invalid params |
| -32603 | Internal error |
| -32001 | Unauthorized |

## 业务错误码

| 错误码 | 说明 |
|--------|------|
| ANKIDROID_NOT_INSTALLED | AnkiDroid 未安装 |
| ANKI_PERMISSION_DENIED | API 权限未授权 |
| ANKI_API_UNAVAILABLE | API 不可用 |
| MODEL_NOT_FOUND | 笔记类型未找到 |
| DECK_OPERATION_FAILED | 牌组操作失败 |
| ADD_NOTE_FAILED | 添加卡片失败 |
| PARTIAL_FAILURE | 部分失败 |
| INVALID_ARGUMENT | 参数无效 |
| INVALID_FRONT | front 无效 |
| INVALID_BACK | back 无效 |
| BATCH_TOO_LARGE | 批量操作过大 |
| UNAUTHORIZED | 未授权 |
| TOOL_NOT_FOUND | 工具未找到 |
| PORT_IN_USE | 端口占用 |
| SERVER_NOT_RUNNING | 服务未运行 |
| INTERNAL_ERROR | 内部错误 |

## 初始化流程

```
Client → Server: POST /mcp
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "clientInfo": {
      "name": "rakahub",
      "version": "1.0"
    }
  }
}

Server → Client:
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": {
      "tools": {}
    },
    "serverInfo": {
      "name": "ankidroid-mcp-bridge",
      "version": "0.1.1"
    }
  }
}

Client → Server: POST /mcp (notification, no response)
{
  "jsonrpc": "2.0",
  "method": "notifications/initialized"
}
```

## 工具调用流程

```
Client → Server: POST /mcp
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "bridge_status",
    "arguments": {}
  }
}

Server → Client:
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\"serverRunning\":true,...}"
      }
    ]
  }
}
```
