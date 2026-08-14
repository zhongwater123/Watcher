"""
ntfy connectivity test script.
Verifies publish, poll, and JSON stream against the self-hosted ntfy server.

Usage:
    python ntfy_connectivity_test.py
"""

import json
import time
import threading
import urllib.request
import urllib.error
import http.client
import sys

NTFY_SERVER = "http://ntfy.shokz-watcher.cn"
TOPIC = "shokz-watcher-test"  # Use a test topic to avoid polluting production
FULL_URL = f"{NTFY_SERVER}/{TOPIC}"

passed = 0
failed = 0


def check(name, condition):
    global passed, failed
    if condition:
        passed += 1
        print(f"  [PASS] {name}")
    else:
        failed += 1
        print(f"  [FAIL] {name}")


def test_server_reachable():
    """Test 1: Server is reachable."""
    print("\n--- Test 1: Server Reachable ---")
    try:
        req = urllib.request.Request(f"{NTFY_SERVER}/v1/health")
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read().decode())
            check("server responds", resp.status == 200)
            check("health is healthy", data.get("healthy") is True)
    except Exception as e:
        check(f"server reachable (error: {e})", False)


def test_publish_plain():
    """Test 2: Publish a plain text message."""
    print("\n--- Test 2: Publish Plain Text ---")
    try:
        payload = "connectivity test message"
        req = urllib.request.Request(
            FULL_URL,
            data=payload.encode("utf-8"),
            method="POST",
        )
        req.add_header("X-Title", "test:connectivity")
        req.add_header("X-Tags", "test")
        with urllib.request.urlopen(req, timeout=10) as resp:
            check("publish returns 200", resp.status == 200)
            body = json.loads(resp.read().decode())
            check("response has id", "id" in body)
            check("response has topic", body.get("topic") == TOPIC)
            check("response event is message", body.get("event") == "message")
    except Exception as e:
        check(f"publish plain text (error: {e})", False)


def test_publish_json_body():
    """Test 3: Publish a JSON relay payload (like the app does)."""
    print("\n--- Test 3: Publish JSON Relay Payload ---")
    try:
        payload = json.dumps({
            "author": "test_script",
            "content": "hello from python test",
            "conversationId": "conv_test_001",
            "ts": int(time.time() * 1000),
        })
        req = urllib.request.Request(
            FULL_URL,
            data=payload.encode("utf-8"),
            method="POST",
        )
        req.add_header("X-Title", "relay:conv_test_001")
        with urllib.request.urlopen(req, timeout=10) as resp:
            check("json publish returns 200", resp.status == 200)
            body = json.loads(resp.read().decode())
            check("message body matches", body.get("message") == payload)
    except Exception as e:
        check(f"publish json (error: {e})", False)


def poll_raw(since="1m"):
    """Low-level poll using http.client to handle chunked/streaming responses."""
    from urllib.parse import urlparse
    parsed = urlparse(NTFY_SERVER)
    host = parsed.hostname
    port = parsed.port or 80
    path = f"/{TOPIC}/json?poll=1&since={since}"
    conn = http.client.HTTPConnection(host, port, timeout=8)
    conn.request("GET", path)
    resp = conn.getresponse()
    data = resp.read().decode("utf-8")
    conn.close()
    return resp.status, data


def test_poll_history():
    """Test 4: Poll recent messages from the topic."""
    print("\n--- Test 4: Poll History ---")
    try:
        status, text = poll_raw("2m")
        check("poll returns 200", status == 200)
        lines = [l for l in text.strip().split("\n") if l.strip()]
        messages = []
        for line in lines:
            msg = json.loads(line)
            if msg.get("event") == "message":
                messages.append(msg)
        check("poll returns messages", len(messages) > 0)
        has_our_msg = any(
            "hello from python test" in (m.get("message") or "")
            for m in messages
        )
        check("poll contains our test message", has_our_msg)
    except Exception as e:
        check(f"poll history (error: {e})", False)


def test_json_stream_subscribe():
    """Test 5: Subscribe via JSON stream and receive a message."""
    print("\n--- Test 5: JSON Stream Subscribe ---")
    received = []
    stop_event = threading.Event()

    def subscriber():
        from urllib.parse import urlparse
        parsed = urlparse(NTFY_SERVER)
        host = parsed.hostname
        port = parsed.port or 80
        path = f"/{TOPIC}/json?since=5s"
        try:
            conn = http.client.HTTPConnection(host, port, timeout=12)
            conn.request("GET", path)
            resp = conn.getresponse()
            while not stop_event.is_set():
                line = resp.readline().decode().strip()
                if not line:
                    continue
                msg = json.loads(line)
                if msg.get("event") == "message":
                    received.append(msg)
                    stop_event.set()
            conn.close()
        except Exception:
            pass

    thread = threading.Thread(target=subscriber, daemon=True)
    thread.start()

    # Give subscriber time to connect
    time.sleep(1.5)

    # Publish a message
    unique_content = f"stream-test-{int(time.time())}"
    payload = json.dumps({
        "author": "test_script",
        "content": unique_content,
        "conversationId": "conv_stream_test",
        "ts": int(time.time() * 1000),
    })
    req = urllib.request.Request(
        FULL_URL,
        data=payload.encode("utf-8"),
        method="POST",
    )
    req.add_header("X-Title", "relay:conv_stream_test")
    try:
        urllib.request.urlopen(req, timeout=5)
    except Exception:
        pass

    # Wait for subscriber to receive
    stop_event.wait(timeout=10)
    thread.join(timeout=2)

    check("stream received message", len(received) > 0)
    if received:
        check(
            "stream message contains our content",
            unique_content in (received[0].get("message") or ""),
        )


def test_parse_relay_payload():
    """Test 6: Verify relay payload can be round-tripped."""
    print("\n--- Test 6: Relay Payload Round-trip ---")
    unique_id = f"conv_roundtrip_{int(time.time())}"
    original = {
        "author": "phone_user",
        "content": "round-trip test",
        "conversationId": unique_id,
        "ts": int(time.time() * 1000),
    }

    # Publish
    req = urllib.request.Request(
        FULL_URL,
        data=json.dumps(original).encode("utf-8"),
        method="POST",
    )
    req.add_header("X-Title", f"relay:{original['conversationId']}")
    try:
        urllib.request.urlopen(req, timeout=5)
    except Exception as e:
        check(f"roundtrip publish (error: {e})", False)
        return

    time.sleep(1)

    # Poll and parse using http.client
    try:
        status, text = poll_raw("30s")
        lines = [l for l in text.strip().split("\n") if l.strip()]
        found = None
        for line in lines:
            msg = json.loads(line)
            if msg.get("event") != "message":
                continue
            try:
                payload = json.loads(msg["message"])
                if payload.get("conversationId") == unique_id:
                    found = payload
                    break
            except (json.JSONDecodeError, KeyError):
                continue

        check("roundtrip message found", found is not None)
        if found:
            check("author preserved", found["author"] == original["author"])
            check("content preserved", found["content"] == original["content"])
            check("conversationId preserved", found["conversationId"] == original["conversationId"])
            check("ts preserved", found["ts"] == original["ts"])
    except Exception as e:
        check(f"roundtrip poll (error: {e})", False)


if __name__ == "__main__":
    print("=" * 50)
    print(f"ntfy Connectivity Test")
    print(f"Server: {NTFY_SERVER}")
    print(f"Topic:  {TOPIC}")
    print("=" * 50)

    test_server_reachable()
    test_publish_plain()
    test_publish_json_body()
    test_poll_history()
    test_json_stream_subscribe()
    test_parse_relay_payload()

    print(f"\n{'=' * 50}")
    print(f"Results: {passed} passed, {failed} failed, {passed + failed} total")
    print(f"{'=' * 50}")

    sys.exit(0 if failed == 0 else 1)
