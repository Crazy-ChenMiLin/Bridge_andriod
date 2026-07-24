# 手动测试清单（Manual Test Checklist）

本清单用于真机/模拟器上验证 `AnkiDroid MCP Bridge` 的端到端行为。
单元测试（`app/src/test`）覆盖协议与仓库逻辑，但以下项目需要与 **真实 AnkiDroid** 联调。

> 测试前准备：
> 1. 安装 AnkiDroid 并至少打开一次（生成数据库）。
> 2. 在 AnkiDroid 中开启「设置 → 高级 → 允许 API 访问」。
> 3. 安装本应用 Debug/Release APK，打开后点击「启动服务」。
> 4. 记录首页「Bearer Token」与端口（默认 8766）。

---

## A. 应用基础

- [ ] 应用首页正常显示，无崩溃。
- [ ] 「Bearer Token」非空（64 位十六进制）。
- [ ] 点击「重新生成 Token」后，旧 Token 立即失效（用旧 Token 调用返回 401），新 Token 生效。
- [ ] 状态显示 `AnkiDroid 已安装 = true`、`权限已授权 = true`。
- [ ] 点击「启动服务」后，状态栏出现常驻通知。
- [ ] 点击通知的「停止」或首页「停止服务」后，通知消失，服务停止。

## B. 健康检查（`GET /health`，无需 Token）

- [ ] `curl http://127.0.0.1:8766/health` 返回
      `{"status":"ok","service":"ankidroid-mcp-bridge","version":"0.1.1"}`。

## C. MCP 协议（`POST /mcp`，需 `Authorization: Bearer <token>`）

可用 `python3 scripts/smoke_test.py` 或 `curl` 验证。未授权访问应返回 401。

- [ ] `initialize`：返回 `protocolVersion=2024-11-05`、`capabilities.tools`、正确的 `serverInfo.version=0.1.1`。
- [ ] `tools/list`：**无需先 initialize** 即可返回 5 个工具（bridge_status / list_decks / ensure_deck / add_basic_note / add_basic_notes）。
- [ ] `bridge_status`：返回 `serverRunning=true`、端口、版本信息（`version` 与 `appVersion` 均为 `BuildConfig.VERSION_NAME`，另有 `apiHostSpecVersion`）。
- [ ] `list_decks`：返回当前 AnkiDroid 牌组列表（含数量）。
- [ ] `ensure_deck`：传入不存在的牌组名 → 创建成功（`created=true`）；再次传入同名 → 返回已有牌组（`created=false`）。
- [ ] `add_basic_note`：成功写入一张卡片，返回 `success=true` 与 `noteId`；在 AnkiDroid 中可见该卡片位于目标牌组。

## D. 业务错误（应返回 `isError=true` 的工具结果，而非 JSON-RPC error）

- [ ] `add_basic_note` 传入空白 `front` → 结果 `isError=true`，`code=INVALID_FRONT`。
- [ ] `add_basic_notes` 传入空 `notes` 数组 → 结果 `isError=true`。
- [ ] `add_basic_notes` 单次超过 100 张 → 结果 `isError=true`，`code=BATCH_TOO_LARGE`。
- [ ] 关闭 AnkiDroid 的「允许 API 访问」后调用 `add_basic_note` → 结果 `isError=true`，`code=ANKI_PERMISSION_DENIED`。
- [ ] 卸载 AnkiDroid 后调用 `add_basic_note` → 结果 `isError=true`，`code=ANKIDROID_NOT_INSTALLED`。

## E. 批量添加与错误索引映射

- [ ] `add_basic_notes` 传入 3 张：第 1 张正常、第 2 张 `front` 为空、第 3 张正常。
      - `requested=3`、`submitted=2`、`succeeded=2`、`failed=1`。
      - `errors[0].index == 1`（指向原始第 2 张），`errors[0].code == INVALID_FRONT`。
      - `noteIds=[]`、`noteIdsAvailable=false`（批量路径不返回单个 noteId）。
      - AnkiDroid 中实际新增 2 张卡片（best-effort 落入目标牌组）。
- [ ] 全部成功时：结果 `isError=false`，`submitted == succeeded == 请求数`，`noteIdsAvailable=false`。
- [ ] 验证批量是**单次 `bulkInsert`** 而非循环插入（可在 `adb logcat` 中观察 ContentProvider 调用次数，或确认卡片写入顺序与单条插入不同）。

## F. 测试脚本（add_test_note.py）

- [ ] 默认（无 `--confirm`）仅打印请求体，**不写入**任何卡片（预览模式）。
- [ ] `python3 scripts/add_test_note.py --confirm`（需先 `export ANKI_MCP_TOKEN=...`）→ 真正写入 1 张测试卡片，返回 `success=true` 与 `noteId`；AnkiDroid 中可见该卡片（带 `mcp-bridge-test` 标签）。

## F. 日志与 UI 联动

- [ ] 启动/停止服务后，首页「运行日志」中出现对应日志（说明前台服务与 UI 共享同一日志实例）。
- [ ] 复制 Token、刷新状态、测试健康检查等按钮均正常工作。

## G. 兼容性

- [ ] 在 Android 8.0（API 26）及以上机型验证可安装并运行（minSdk=26）。
- [ ] 不同厂商系统（华为/小米/OPPO/vivo/三星等）前台服务在后台不被轻易杀掉（参考 README 后台管理建议）。

---

## 快捷验证命令

```bash
# 健康检查（无需 Token）
curl -s http://127.0.0.1:8766/health

# 假设 TOKEN 已导出
export TOKEN="你的BearerToken"

# initialize
curl -s -X POST http://127.0.0.1:8766/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{}}}'

# tools/list
curl -s -X POST http://127.0.0.1:8766/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# add_basic_note
curl -s -X POST http://127.0.0.1:8766/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"add_basic_note","arguments":{"deck":"MCP测试","front":"问题?","back":"答案"}}}'
```
