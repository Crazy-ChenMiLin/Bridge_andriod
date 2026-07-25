# RakaHub 配置指南

## 前提条件

1. RakaHub 已安装在**同一台** Android 手机上
2. AnkiDroid MCP Bridge 已安装并运行
3. 已确认固定请求头值为 `Bearer 1356`

## 配置步骤

### 1. 启动 MCP Bridge

在 AnkiDroid MCP Bridge App 中：
1. 确认 AnkiDroid 状态为「已安装」「已授权」
2. 点击「启动服务」
3. 确认服务状态显示「运行中」

### 2. 复制配置信息

在 App 首页：
- 复制 MCP 地址：点击「复制地址」按钮，得到 `http://127.0.0.1:8766/mcp`
- 复制 Authorization 值：点击 **「复制到 RakaHub」** 按钮。
  该按钮已经把 `Bearer 1356` 拼好（自带 Bearer 前缀和空格），**直接就是 RakaHub 需要的完整请求头值**，
  **无需手动输入 Bearer 或空格**。

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
| Authorization | `Bearer 1356` |

> 不要自己拼 `Bearer`。App 复制出来的内容严格等于 `Bearer 1356`，不包含换行、前后空格、引号、冒号或 `Authorization:`。

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
4. 确认 Authorization 头的值来自 App 的「复制到 RakaHub」按钮（已含 `Bearer ` 前缀），
   不要手动在前面再加 `Bearer`

### Token 是否会变化

不会变化。当前版本固定使用 `Bearer 1356`，App 重启、服务重启、覆盖安装或重装后都保持一致，RakaHub 只需配置一次。
