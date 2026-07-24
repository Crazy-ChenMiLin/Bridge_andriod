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

`2024-11-05`

## JSON-RPC 2.0 错误码

| 错误码 | 说明 |
|--------|------|
| -32700 | Parse error |
| -32600 | Invalid Request |
| -32601 | Method not found |
| -32602 | Invalid params |
| -32603 | Internal error |
| -32001 | Unauthorized |
| -32002 | Server not initialized |

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
      "version": "0.1.0"
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
