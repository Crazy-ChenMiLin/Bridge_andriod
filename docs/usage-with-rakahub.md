# 用 RakaHub + AI 聊天生成 Anki 卡片

本桥接（AnkiDroid MCP Bridge）把 AnkiDroid 的能力暴露成 31 个 MCP 工具。
只要 RakaHub 作为 MCP Client 连上本桥接，并把这些工具交给 AI，就能实现：
**在对话中聊天 → AI 自动调工具 → 卡片进入 AnkiDroid**。

---

## 1. 前置条件（v0.2.3）

- AnkiDroid 已安装，且已在本 App 内「授权 AnkiDroid」（API 权限=已授权）
- 本 App 已启动 MCP 服务（默认 `127.0.0.1:8766`）
- RakaHub 已添加本桥接为 MCP Server：
  - 类型：Streamable HTTP
  - 地址：`http://127.0.0.1:8766/mcp`
  - 认证：点击本 App 内「复制到 RakaHub」按钮，把得到的完整值 `Bearer 1356`（已含 `Bearer ` 前缀）
    直接粘贴到 RakaHub 的「请求头值」中，**不要手动输入 Bearer 或空格**
- RakaHub 能列出 31 个工具即为连接成功

> 如果 RakaHub 侧显示「健康检查」异常，但 `list_decks` / `add_basic_note` 都能正常调用，
> 那是 RakaHub 的连通性探针与 `/health` 端点格式不匹配，**不影响卡片生成**。

---

## 2. 工具说明

| 工具名 | 作用 | 何时用 |
|--------|------|--------|
| `bridge_status` | 报告服务/AnkiDroid 状态 | 排查问题时用 |
| `list_decks` | 列出所有牌组 | 想确认牌组存在、选目标牌组 |
| `ensure_deck` | 确保牌组存在（没有就建） | 写入前先确保目标牌组 |
| `add_basic_note` | 加一张 Basic 卡片（front/back/deck） | 提炼出单个知识点时（兼容旧用法） |
| `add_basic_notes` | 批量加 Basic 卡片（≤100 张） | 一段对话提炼出多个 Basic 知识点时 |
| `list_note_types` | 列出本机全部笔记类型（含字段） | **写入任意笔记类型前必须先调用** |
| `get_note_type` | 获取某笔记类型的完整字段/模板/CSS | 写入前确认字段名拼写 |
| `add_note` | 按 `noteTypeId` 写入一张**任意字段**笔记 | 写入非 Basic 的笔记类型（如面试题、算法题） |
| `add_notes` | 批量按任意笔记类型写入（≤100 张） | 一次对话提炼出多个不同字段结构的笔记 |

> `add_basic_note` / `add_basic_notes` 在 v0.2.0 中已内部复用通用写入链路，行为与之前一致，可继续放心使用。

### PC MCP 兼容别名

这些工具名对齐电脑端 Anki MCP / AnkiConnect 常见接口，但只保留 AnkiDroid 官方 ContentProvider 能真实支持的能力：

| 工具名 | 对应能力 |
|--------|----------|
| `listDecks` | 列出牌组（返回 `deckNames` 和基础 deck 信息；安卓端不提供完整 PC 统计） |
| `createDeck` | 创建/确保牌组存在 |
| `modelNames` | 列出笔记类型名称 |
| `modelFieldNames` | 按 `modelName` 获取字段名 |
| `addNote` | 按 `deckName` + `modelName` + `fields` 写入单条笔记；成功返回 noteId，重复返回 `null` |
| `addNotes` | 批量写入 ≤100 条；支持 PC 常见的每条 note 自带 `deckName`/`modelName`，也支持顶层共享 `deckName`/`modelName`；返回 noteId/`null` 数组 |
| `findNotes` | 使用 Anki 搜索语法查找 noteId |
| `notesInfo` | 查询 note 的字段、标签、笔记类型；字段形状为 `fields.字段名.value/order` |
| `updateNoteFields` | 更新已有 note 的字段 |
| `getTags` | 列出/过滤标签 |
| `addTags` | 给 note 增加标签 |
| `removeTags` | 从 note 移除标签 |
| `replaceTags` | 在指定 note 上把一个标签替换成另一个标签 |
| `modelTemplates` | 读取笔记类型的卡片模板 |
| `modelStyling` | 读取笔记类型 CSS |
| `get_cards` | 按牌组/状态读取卡片 |
| `get_due_cards` | 从 AnkiDroid 调度队列读取待复习卡片 |
| `present_card` | 读取单张卡片正面，必要时带答案 |
| `changeDeck` | 按 cardId 移动卡片到目标牌组 |
| `rate_card` | 按 cardId 提交 1-4 的复习评分 |
| `deckStats` | 读取单个牌组的安卓端基础卡片计数 |
| `collection_stats` | 汇总全库安卓端基础卡片计数 |

`addNote` / `addNotes` 默认做 collection 级重复检测（按该笔记类型首字段精确匹配）。如确实要允许重复，可传 `allowDuplicate=true`，也兼容 PC 常见的 `options.allowDuplicate=true`。安卓版不支持 PC 端更细的 `duplicateScopeOptions`。

如果 PC 客户端在 `addNote` / `addNotes` 里传 `audio`、`picture`、`video`，安卓版会直接返回“不支持媒体附件”的错误，不会静默忽略后写入一张缺媒体的卡。

PC 兼容别名会尽量同时识别常见驼峰/下划线参数，例如 `deckName`/`deck_name`、`modelName`/`model_name`、`cardId`/`card_id`、`showAnswer`/`show_answer`。

### 安卓端不暴露的 PC 专属能力

以下能力依赖电脑端 Anki GUI、AnkiConnect 模型编辑/媒体/同步接口，或 AnkiDroid 公开 API 未暴露的低层能力，因此安卓版不放进 `tools/list`：

`guiAddCards`、`guiBrowse`、`guiCurrentCard`、`guiSelectedNotes`、`guiEditNote`、`guiUndo`、`guiSelectCard`、`guiDeckOverview`、`guiDeckBrowser`、`guiShowAnswer`、`guiShowQuestion`、`sync`、`review_stats`、`deleteNotes`、`createModel`、`addModelField`、`renameModelField`、`removeModelField`、`repositionModelField`、`updateModelTemplates`、`updateModelStyling`、`storeMediaFile`、`getMediaFilesNames`、`retrieveMediaFile`、`deleteMediaFile`、`clearUnusedTags`。

---

## 3. 写入任意笔记类型的标准流程（v0.2.0 新增）

在不知道字段结构时，**不要**直接调 `add_note`。标准流程：

1. `list_note_types` → 拿到可用 `noteTypeId` 与字段名；
2. 按用户需求选 `noteTypeId`；
3. `get_note_type` → 拿到该类型的**有序字段名**；
4. 依据字段名生成 `fields`（键名必须与返回一致；未知字段会被拒绝）；
5. **写入前向用户展示卡片内容并确认**；
6. 确认后调 `add_note`（单张）或 `add_notes`（批量）；
7. 检查返回 `persisted`（数据是否落库）与 `refreshNotified`（是否通知本地刷新）。

---

## 4. 给 AI 的系统提示词模板（直接复制）

把下面这段作为 RakaHub 里该对话的 **系统提示 / System Prompt**：

```
你是用户的记忆助手，运行在用户的手机上，可以通过 MCP 工具直接写入 AnkiDroid。

规则：
1. Basic 卡片用 add_basic_note / add_basic_notes（front/back/deck），deck 不存在会自动创建。
2. 写入非 Basic 的笔记类型前，必须先 list_note_types 选 noteTypeId，再 get_note_type 拿字段名，
   然后按字段名生成 fields 调用 add_note / add_notes。不要凭空猜测字段名。
3. fields 的键名必须与 get_note_type 返回一致；出现未知字段会被拒绝（isError=true）。
4. 每次写入都必须在 deck 参数里显式给出完整牌组名；add_* 会自动确保牌组存在（不存在即创建），多数情况无需先调 ensure_deck。注意：MCP 工具无状态，ensure_deck 不会为后续调用“记住”当前牌组，deck 绝不能留空（留空会返回 DECK_NAME_EMPTY）。
5. 一次对话里出现多个知识点时，优先用批量接口（add_basic_notes / add_notes）一次写入。
6. 不要在用户未确认前写入；写卡前先展示内容让用户确认。
7. 写完后检查 persisted / refreshNotified：persisted=true 表示数据已落库。
8. 不要编造卡片内容；只把已确认正确的知识写进 AnkiDroid。
9. 每写入一批卡片后，简短告诉用户：写入了几张、进了哪个牌组、noteTypeId。
```

---

## 5. 推荐的工作流

1. 在 RakaHub 新建对话，贴上上面的系统提示词。
2. 像平常一样跟 AI 聊天：问问题、让它讲解、让它总结资料。
3. AI 会在合适时机调用 `add_basic_note` / `add_basic_notes` 或 `add_note` / `add_notes`。
4. 打开 AnkiDroid 就能看到新卡片，正常复习即可。
5. 想指定牌组：直接说「把这些放进『英语词汇』牌组」，AI 会把 `deck` 参数设为该名字。

---

## 6. 关于空 deck：不会“继承”当前牌组（重要）

MCP 工具是无状态的：`ensure_deck` 只负责“确保某个牌组存在”，**不会**为后续 `add_*` 调用设置“当前牌组”。

- ❌ **错误**：先 `ensure_deck(name="英语词汇")`，再 `add_notes(deck="", ...)`，指望沿用刚才的牌组。结果：服务端收到空 `deck`，返回业务错误 `DECK_NAME_EMPTY`（`isError=true`），卡片不会写入。
- ✅ **推荐**：直接 `add_note` / `add_notes`，在 `deck` 参数里显式给出牌组名；牌组不存在会自动创建，无需先调 `ensure_deck`。

  ```
  add_notes(deck="英语词汇", notes=[...])
  ```

- ✅ **也可**：先 `ensure_deck(name="英语词汇")` 预创建，再 `add_notes(deck="英语词汇", ...)`——但 `deck` 必须**每次都显式传同一个名字**，不能留空。

> 一句话：每次写入都在 `deck` 参数里写全牌组名；不要依赖任何“上次调用”的状态。

---

## 7. 关于同步的边界（重要）

本桥接写入后是**本地刷新通知**，不是 AnkiWeb 云同步：

- 写入成功后会回读验证（`persisted`）；
- 然后通过 `ContentResolver.notifyChange` 通知 AnkiDroid 数据变化（`refreshNotified`）；
- `refreshNotified=true` **不代表** 已做 AnkiWeb 云同步；
- AnkiWeb 云同步仍由 AnkiDroid 自身的自动/手动同步负责；本应用不调用任何云同步能力。

---

## 8. 排错

| 现象 | 原因 | 处理 |
|------|------|------|
| RakaHub 连不上 | 服务没启动 / 端口不对 / 鉴权值错 | App 内启动服务，核对地址，并用「复制到 RakaHub」复制 Authorization 值（勿手输 Bearer） |
| 工具调用报权限错误 | AnkiDroid 未授权 | App 内点「授权 AnkiDroid」并允许 |
| add_note 报 FIELD_NOT_FOUND | 字段名拼错或多了未知字段 | 先 get_note_type 拿准确字段名再生成 fields |
| 卡片没进目标牌组 | 用了批量接口且 AnkiDroid 未返回 noteId | 属已知限制（批量路径）不影响卡片写入；单张接口可精确进组 |
| persisted=false | 写入后回读验证未通过 | 卡片可能未真正落库，建议重试或检查 AnkiDroid 状态 |
| add_* 报 DECK_NAME_EMPTY | deck 参数为空或只含空白 | 在 deck 参数里显式给出牌组名（add_* 会自动创建），不要依赖 ensure_deck 的状态继承 |
| RakaHub 显示健康异常但功能正常 | 探针格式不匹配 | 可忽略，不影响生成 |
