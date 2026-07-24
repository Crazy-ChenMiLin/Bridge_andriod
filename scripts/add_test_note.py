#!/usr/bin/env python3
"""
通过 AnkiDroid MCP Bridge 写入一张测试卡片。

默认行为（无 --confirm）：仅打印将要发送的 MCP 请求，**不会**真正写入任何卡片。
使用 --confirm 才会真正调用 add_basic_note 工具。

使用方法:
  export ANKI_MCP_TOKEN="你的Token"
  # 预览（不写入）
  python3 add_test_note.py
  # 真正写入
  python3 add_test_note.py --confirm
  # 自定义 host/port/deck
  python3 add_test_note.py --confirm --host 127.0.0.1 --port 8766 --deck "MCP Test"
"""

import argparse
import json
import os
import sys
import urllib.request
import urllib.error

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8766
DEFAULT_DECK = "MCP Test"


def build_test_note():
    """构造一张明显的测试卡片，便于在 AnkiDroid 中识别并删除。"""
    from datetime import datetime
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    return {
        "front": f"MCP Bridge 测试 ({ts})",
        "back": f"这是一张由 AnkiDroid MCP Bridge 创建的测试卡片。时间: {ts}",
        "tags": ["mcp-bridge-test"],
    }


def mcp_request(base_url, token, method, params=None):
    """发送 MCP JSON-RPC 请求，返回解析后的 JSON（或 None）。"""
    body = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": method,
    }
    if params is not None:
        body["params"] = params

    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        f"{base_url}/mcp",
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        print(f"  HTTP {e.code}: {e.read().decode()}")
        return None


def main():
    parser = argparse.ArgumentParser(description="通过 AnkiDroid MCP Bridge 写入一张测试卡片")
    parser.add_argument("--host", default=DEFAULT_HOST, help=f"MCP 服务地址 (默认 {DEFAULT_HOST})")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"MCP 服务端口 (默认 {DEFAULT_PORT})")
    parser.add_argument("--deck", default=DEFAULT_DECK, help=f"目标牌组 (默认 '{DEFAULT_DECK}')")
    parser.add_argument("--confirm", action="store_true", help="真正写入卡片（不加此参数则只预览）")
    args = parser.parse_args()

    token = os.environ.get("ANKI_MCP_TOKEN")
    if not token:
        print("ERROR: ANKI_MCP_TOKEN 环境变量未设置")
        print("用法: export ANKI_MCP_TOKEN='your-token' && python3 add_test_note.py --confirm")
        sys.exit(1)

    base_url = f"http://{args.host}:{args.port}"
    note = build_test_note()
    params = {
        "name": "add_basic_note",
        "arguments": {
            "deck": args.deck,
            "front": note["front"],
            "back": note["back"],
            "tags": note["tags"],
        },
    }

    print(f"=== AnkiDroid MCP Bridge 测试卡片 ===")
    print(f"目标: {base_url}")
    print(f"牌组: {args.deck}")
    print(f"Token: {token[:8]}...")
    print(f"请求体: {json.dumps(params, ensure_ascii=False)}")

    if not args.confirm:
        print()
        print("⚠️  未提供 --confirm，仅预览，未写入任何卡片。")
        print("    需要真正写入请执行: python3 add_test_note.py --confirm")
        sys.exit(0)

    print()
    print("[WRITE] 调用 add_basic_note ... ", end="")
    resp = mcp_request(base_url, token, "tools/call", params)
    if resp is None:
        print("FAIL: 无响应")
        sys.exit(1)
    if "error" in resp:
        print(f"FAIL: {resp['error']}")
        sys.exit(1)

    result = resp.get("result", {})
    is_error = result.get("isError", False)
    content = result.get("content", [{}])[0].get("text", "{}")
    try:
        payload = json.loads(content)
    except json.JSONDecodeError:
        payload = {}

    if is_error:
        print(f"FAIL: {payload}")
        sys.exit(1)

    print(f"PASS (noteId={payload.get('noteId')}, deck={payload.get('deck')})")
    sys.exit(0)


if __name__ == "__main__":
    main()
