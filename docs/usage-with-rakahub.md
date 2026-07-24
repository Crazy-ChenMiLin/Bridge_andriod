# 用 RakaHub + AI 聊天生成 Anki 卡片

本桥接（AnkiDroid MCP Bridge）把 AnkiDroid 的能力暴露成 5 个 MCP 工具。
只要 RakaHub 作为 MCP Client 连上本桥接，并把这 5 个工具交给 AI，就能实现：
**在对话中聊天 → AI 自动调工具 → 卡片进入 AnkiDroid**。

---

## 1. 前置条件（都已在 v0.1.3 验证通过）

- AnkiDroid 已安装，且已在本 App 内「授权 AnkiDroid」（API 权限=已授权）
- 本 App 已启动 MCP 服务（默认 `127.0.0.1:8766`）
- RakaHub 已添加本桥接为 MCP Server：
  - 类型：Streamable HTTP
  - 地址：`http://127.0.0.1:8766/mcp`
  - 认证：Bearer Token（本 App 内「Bearer Token」卡片里的字符串）
- RakaHub 能列出 5 个工具即为连接成功

> 如果 RakaHub 侧显示「健康检查」异常，但 `list_decks` / `add_basic_note` 都能正常调用，
> 那是 RakaHub 的连通性探针与 `/health` 端点格式不匹配，**不影响卡片生成**。

---

## 2. 五个工具说明

| 工具名 | 作用 | 何时用 |
|--------|------|--------|
| `bridge_status` | 报告服务/AnkiDroid 状态 | 排查问题时用 |
| `list_decks` | 列出所有牌组 | 想确认牌组存在、选目标牌组 |
| `ensure_deck` | 确保牌组存在（没有就建） | 写入前先确保目标牌组 |
| `add_basic_note` | 加一张卡片（front/back/deck） | 提炼出单个知识点时 |
| `add_basic_notes` | 批量加卡片（≤100 张） | 一段对话提炼出多个知识点时 |

卡片是 Basic 类型：`front`=问题/提示，`back`=答案/解释。`deck` 不存在会自动创建。

---

## 3. 给 AI 的系统提示词模板（直接复制）

把下面这段作为 RakaHub 里该对话的 **系统提示 / System Prompt**：

```
你是用户的记忆助手，运行在用户的手机上，可以通过 MCP 工具直接写入 AnkiDroid。

规则：
1. 当对话中出现值得长期记忆的知识点（定义、公式、概念、单词、流程、易错点等）时，
   主动调用 add_basic_note 把它变成复习卡片。
2. front 写能唤起回忆的「问题/提示」，back 写简洁准确的「答案/解释」。
3. 默认牌组用 "MCP Study"；用户明确指定牌组时改用用户给的名字。
4. 一次对话里出现多个知识点时，优先用 add_basic_notes 一次批量写入。
5. 写卡前先用 ensure_deck 确保目标牌组存在（add_basic_note 也会自动建，可省略）。
6. 不要编造卡片内容；只把已确认正确的知识写进 AnkiDroid。
7. 每写入一批卡片后，简短告诉用户：写入了几张、进了哪个牌组。
8. 不要频繁打断对话去写卡；可在回答完用户问题后，顺手把本回合的知识点固化。
```

---

## 4. 推荐的工作流

1. 在 RakaHub 新建对话，贴上上面的系统提示词。
2. 像平常一样跟 AI 聊天：问问题、让它讲解、让它总结资料。
3. AI 会在合适时机调用 `add_basic_note` / `add_basic_notes`。
4. 打开 AnkiDroid 就能看到新卡片，正常复习即可。
5. 想指定牌组：直接说「把这些放进『英语词汇』牌组」，AI 会把 `deck` 参数设为该名字。

---

## 5. 排错

| 现象 | 原因 | 处理 |
|------|------|------|
| RakaHub 连不上 | 服务没启动 / 端口不对 / Token 错 | App 内启动服务，核对地址与 Bearer Token |
| 工具调用报权限错误 | AnkiDroid 未授权 | App 内点「授权 AnkiDroid」并允许 |
| 卡片没进目标牌组 | 用了批量接口且 AnkiDroid 未返回 noteId | 属已知限制（批量路径）不影响卡片写入；单张接口可精确进组 |
| RakaHub 显示健康异常但功能正常 | 探针格式不匹配 | 可忽略，不影响生成 |
