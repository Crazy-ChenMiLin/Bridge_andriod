# RakaHub 配置指南

## 前提条件

1. RakaHub 已安装在**同一台** Android 手机上
2. AnkiDroid MCP Bridge 已安装并运行
3. 已获取 Bearer Token

## 配置步骤

### 1. 启动 MCP Bridge

在 AnkiDroid MCP Bridge App 中：
1. 确认 AnkiDroid 状态为「已安装」「已授权」
2. 点击「启动服务」
3. 确认服务状态显示「运行中」

### 2. 复制配置信息

在 App 首页：
- 复制 MCP 地址：`http://127.0.0.1:8766/mcp`
- 复制 Token（点击 Token 旁的复制按钮）

### 3. 在 RakaHub 中添加 MCP Server

| 配置项 | 值 |
|--------|-----|
| 名称 | AnkiDroid MCP |
| 传输类型 | Streamable HTTP |
| 服务器地址 | `http://127.0.0.1:8766/mcp` |

### 4. 配置鉴权

添加自定义请求头：

| 名称 | 值 |
|------|-----|
| Authorization | `Bearer 你的Token` |

例如：`Bearer a1b2c3d4e5f6...`（32 字节，64 位十六进制字符）

## 验证连接

在 RakaHub 中发送以下测试提示词：

```
调用 bridge_status，告诉我 AnkiDroid 和 MCP 服务是否正常，不要修改任何卡片。
```

预期响应应包含：
- `serverRunning: true`
- `ankiDroidInstalled: true`
- `ankiPermissionGranted: true`

## 第二个测试

```
读取我的 AnkiDroid 牌组列表，不要创建或修改任何内容。
```

预期响应应包含你的所有 AnkiDroid 牌组。

## 制卡测试

```
把下面内容拆分成 5 张简洁的问答卡，调用 add_basic_notes，一次性写入「Java面试」牌组。
添加前先��我展示卡片内容，得到确认后再写入。

Java 基础知识：
- IOC：控制反转，将对象的创建和依赖管理交给容器
- AOP：面向切面编程，将横切关注点与业务逻辑分离
- DI：依赖注入，IOC 的一种实现方式
- Spring Bean：由 Spring 容器管理的对象
- MVC：Model-View-Controller 设计模式
```

## 常见问题

### 连接失败

1. 确认 MCP Bridge 服务正在运行（查看通知栏）
2. 确认 RakaHub 和 Bridge 在同一台手机上
3. 确认地址是 `http://127.0.0.1:8766/mcp`（注意是 http 不是 https）
4. 确认 Token 已正确复制（包括 `Bearer ` 前缀）

### Token 过期

如果在 App 中重新生成了 Token，需要在 RakaHub 中更新 Authorization 头。
