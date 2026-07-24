#!/usr/bin/env python3
"""
AnkiDroid MCP Bridge 冒烟测试脚本

测试内容:
- GET /health
- MCP initialize
- MCP tools/list
- MCP bridge_status

使用方法:
  export ANKI_MCP_TOKEN="你的Token"
  python3 smoke_test.py [host] [port]

默认 host=127.0.0.1, port=8766
"""

import json
import os
import sys
import urllib.request
import urllib.error


def test_health(base_url):
    """测试 GET /health"""
    print("[TEST] GET /health ... ", end="")
    try:
        req = urllib.request.Request(f"{base_url}/health")
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read())
            assert data["status"] == "ok", f"Expected status=ok, got {data.get('status')}"
            assert data["service"] == "ankidroid-mcp-bridge"
            print("PASS")
            return True
    except Exception as e:
        print(f"FAIL: {e}")
        return False


def mcp_request(base_url, token, method, params=None):
    """发送 MCP JSON-RPC 请求"""
    body = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": method
    }
    if params is not None:
        body["params"] = params

    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        f"{base_url}/mcp",
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}"
        }
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        print(f"  HTTP {e.code}: {e.read().decode()}")
        return None


def test_mcp_initialize(base_url, token):
    """测试 MCP initialize"""
    print("[TEST] MCP initialize ... ", end="")
    try:
        resp = mcp_request(base_url, token, "initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "smoke-test", "version": "1.0"}
        })
        assert resp is not None, "No response"
        assert "result" in resp, f"Expected result, got {resp}"
        assert resp["result"]["protocolVersion"] == "2024-11-05"
        print("PASS")
        return True
    except Exception as e:
        print(f"FAIL: {e}")
        return False


def test_mcp_tools_list(base_url, token):
    """测试 MCP tools/list"""
    print("[TEST] MCP tools/list ... ", end="")
    try:
        resp = mcp_request(base_url, token, "tools/list")
        assert resp is not None, "No response"
        assert "result" in resp, f"Expected result, got {resp}"
        tools = resp["result"]["tools"]
        tool_names = [t["name"] for t in tools]
        assert "bridge_status" in tool_names, f"bridge_status not in {tool_names}"
        assert "list_decks" in tool_names
        assert "ensure_deck" in tool_names
        assert "add_basic_note" in tool_names
        assert "add_basic_notes" in tool_names
        print(f"PASS ({len(tools)} tools)")
        return True
    except Exception as e:
        print(f"FAIL: {e}")
        return False


def test_mcp_bridge_status(base_url, token):
    """测试 MCP bridge_status"""
    print("[TEST] MCP bridge_status ... ", end="")
    try:
        resp = mcp_request(base_url, token, "tools/call", {
            "name": "bridge_status",
            "arguments": {}
        })
        assert resp is not None, "No response"
        assert "result" in resp, f"Expected result, got {resp}"
        content = resp["result"]["content"][0]["text"]
        status = json.loads(content)
        assert "serverRunning" in status
        assert "ankiDroidInstalled" in status
        assert "ankiPermissionGranted" in status
        print(f"PASS (serverRunning={status['serverRunning']})")
        return True
    except Exception as e:
        print(f"FAIL: {e}")
        return False


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8766
    base_url = f"http://{host}:{port}"

    token = os.environ.get("ANKI_MCP_TOKEN")
    if not token:
        print("ERROR: ANKI_MCP_TOKEN 环境变量未设置")
        print("用法: export ANKI_MCP_TOKEN='your-token' && python3 smoke_test.py")
        sys.exit(1)

    print(f"=== AnkiDroid MCP Bridge 冒烟测试 ===")
    print(f"目标: {base_url}")
    print(f"Token: {token[:8]}...")
    print()

    results = []
    results.append(("Health Check", test_health(base_url)))
    results.append(("MCP Initialize", test_mcp_initialize(base_url, token)))
    results.append(("MCP Tools/List", test_mcp_tools_list(base_url, token)))
    results.append(("MCP Bridge Status", test_mcp_bridge_status(base_url, token)))

    print()
    passed = sum(1 for _, r in results if r)
    failed = sum(1 for _, r in results if not r)
    print(f"=== 结果: {passed}/{len(results)} 通过, {failed} 失败 ===")

    for name, r in results:
        status = "PASS" if r else "FAIL"
        print(f"  [{status}] {name}")

    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
