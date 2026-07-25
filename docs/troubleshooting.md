# 故障排查指南

## Connection refused

**症状**: 连接 MCP 服务时提示 "Connection refused"

**排查步骤**:
1. 确认已在 App 中点击「启动服务」
2. 检查通知栏是否有「Anki MCP 服务正在运行」
3. 检查端口是否正确（默认 8766）
4. 尝试访问 `http://127.0.0.1:8766/health`

## 401 Unauthorized

**症状**: MCP 请求返回 401 错误

**排查步骤**:
1. 确认已正确复制 Token
2. 检查请求头格式：`Authorization: Bearer 1356`（注意 Bearer 后有空格）
3. 确认没有把请求头名称一起粘到值里；RakaHub 的请求头名称是 `Authorization`，请求头值只填 `Bearer 1356`

## AnkiDroid 未安装

**症状**: bridge_status 返回 `ankiDroidInstalled: false`

**排查步骤**:
1. 从 Google Play 或 GitHub Releases 安装 AnkiDroid
2. 包名必须为 `com.ichi2.anki`
3. 安装后返回 App 点击「刷新」

## 权限不足

**症状**: bridge_status 返回 `ankiPermissionGranted: false`

**排查步骤**:
1. 在 App 中点击「授权 AnkiDroid」
2. 在 AnkiDroid 中：设置 → 高级 → 允许 API 访问
3. 返回 App 点击「刷新」确认

## 端口被占用

**症状**: 启动服务失败

**排查步骤**:
1. 在 App 中修改端口号（服务停止时可修改）
2. 端口范围：1024-65535
3. 检查是否有其他应用使用该端口

## RakaHub 无法访问 HTTP

**症状**: RakaHub 连接超时

**排查步骤**:
1. RakaHub 必须运行在同一台手机上
2. 确认地址为 `http://127.0.0.1:8766/mcp`（不是 `https`）
3. 确认已配置正确的 Authorization 头

## App 进入后台后服务停止

**症状**: 切换到其他 App 后 MCP 服务停止

**排查步骤**:
1. 前台服务通过常驻通知保持运行
2. 关闭电池优化：设置 → 应用 → AnkiDroid MCP Bridge → 电池 → 不限制

## 手机系统杀后台

**症状**: 一段时间后 MCP 服务自动停止

### 华为/荣耀
设置 → 应用 → 应用启动管理 → AnkiDroid MCP Bridge → 手动管理
（允许自启动、关联启动、后台活动）

### 小米/Redmi
设置 → 应用设置 → 授权管理 → 自启动管理 → 允许

### OPPO/一加
设置 → 电池 → 应用耗电管理 → AnkiDroid MCP Bridge → 不优化

### vivo/iQOO
设置 → 电池 → 后台高耗电 → 允许

### 三星
设置 → 设备维护 → 电池 → 不监控的应用 → 添加

### 原生 Android
设置 → 应用 → AnkiDroid MCP Bridge → 电池 → 不限制

## 牌组创建失败

**症状**: ensure_deck 返回错误

**排查步骤**:
1. 确认 AnkiDroid API 权限已授予
2. 牌组名称不超过 200 字符
3. 牌组名称不包含特殊字符

## 找不到 Basic 模型

**症状**: add_basic_note 返回 MODEL_NOT_FOUND 错误

**排查步骤**:
1. 在 AnkiDroid 中确认存在包含 Front 和 Back 字段的基础笔记类型
2. App 会自动尝试创建 "MCP Basic" 模型
3. 如果自动创建失败，手动在 AnkiDroid 中创建一个 Basic 笔记类型

## 卡片无法在 AnkiDroid 中打开

**症状**: 添加的卡片在 AnkiDroid 中不显示或格式异常

**排查步骤**:
1. 确认使用的笔记类型是 Basic（Front + Back 两字段）
2. 检查 AnkiDroid 中的模型字段名是否为 "Front" 和 "Back"
3. 在 AnkiDroid 中同步一次

## 获取日志

App 首页底部有日志区域，显示最近 100 条日志。
日志不包含 Token 和完整卡片背面内容，可安全分享用于排查问题。
