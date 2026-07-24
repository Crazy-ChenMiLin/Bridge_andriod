# AnkiDroid API 使用说明

## 使用的 API

本 App 通过 **AnkiDroid ContentProvider** 与 AnkiDroid 通信，不依赖额外的 JAR 库。

### ContentProvider Authority

```
com.ichi2.anki.flashcards
```

### 权限

```xml
<uses-permission android:name="com.ichi2.anki.permission.READ_WRITE_DATABASE" />
```

用户需要在 AnkiDroid 设置中手动启用 API 访问权限。

## 实际使用的 ContentProvider URI

| 操作 | URI | 方法 |
|------|-----|------|
| 查询牌组 | `content://com.ichi2.anki.flashcards/decks/` | query |
| 创建牌组 | `content://com.ichi2.anki.flashcards/decks/` | insert |
| 查询模型 | `content://com.ichi2.anki.flashcards/models/` | query |
| 创建模型 | `content://com.ichi2.anki.flashcards/models/` | insert |
| 添加笔记 | `content://com.ichi2.anki.flashcards/notes/` | insert |

## 数据格式

### 字段分隔符

AnkiDroid 使用 `\u001f` (ASCII 31, Unit Separator) 分隔笔记字段。

### 牌组 (Deck)

ContentValues:
- `name`: 牌组名称 (String)

Cursor columns:
- `_id`: 牌组 ID (Long)
- `name`: 牌组名称 (String)

### 笔记类型 (Model)

ContentValues:
- `name`: 模型名称 (String)
- `flds`: 字段名，用 `\u001f` 分隔 (String)
- `css`: CSS 样式 (String)
- `did`: 默认牌组 ID (Long)

Cursor columns:
- `_id`: 模型 ID (Long)
- `name`: 模型名称 (String)
- `flds`: 字段名 (String, `\u001f` 分隔)

### 笔记 (Note)

ContentValues:
- `mid`: 模型 ID (Long)
- `did`: 牌组 ID (Long)
- `flds`: 字段内容，用 `\u001f` 分隔 (String)
- `tags`: 标签，空格分隔 (String)

## 模型解析方式

1. 查询 `content://com.ichi2.anki.flashcards/models/`
2. 遍历每个模型，检查 `flds` 列
3. 按 `\u001f` 分割字段名
4. 查找包含 "Front" 和 "Back" 字段且只有 2 个字段的模型
5. 若找不到，尝试创建 "MCP Basic" 模型

## 已验证的 AnkiDroid 版本

- AnkiDroid 2.18+ (基于 ContentProvider API 的公开文档)

## 未支持的功能

以下 AnkiDroid API 能力在 MVP 中未使用：

- 媒体文件管理
- 复习调度 API
- 卡片浏览 API
- 统计信息 API
- 同步 API

## API 兼容性注意事项

- ContentProvider API 的具体行为可能因 AnkiDroid 版本而异
- 字段分隔符 `\u001f` 是 AnkiDroid 内部约定
- 模型创建功能在某些版本中可能受限
- 建议使用 AnkiDroid 2.18 或更高版本
