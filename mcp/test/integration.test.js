#!/usr/bin/env node
/**
 * Integration test for watcher-mcp server.
 * Spawns a mock gateway HTTP server, then spawns the MCP server
 * and exercises all tools via JSON-RPC over stdio.
 */
import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";
import fs from "node:fs";
import os from "node:os";
import assert from "node:assert/strict";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SERVER_JS = path.resolve(__dirname, "..", "server.js");
const NTFY_CLIENT_JS = path.resolve(__dirname, "..", "lib", "ntfy-client.js");

const API_KEY = "test-api-key-12345";
const DEVICE_ID = "test-device-001";
const BINDING_TOKEN = "tok_request_123";

// --- Mock Gateway ---

function createMockGateway() {
  const tasks = new Map();
  let taskCounter = 0;
  const pairingRequests = new Map();
  // Relay chat now uses ntfy - no mock gateway relay state needed

  const server = createServer((req, res) => {
    const url = new URL(req.url, `http://localhost`);
    const apiKey = req.headers["x-api-key"];
    const bearerToken = (req.headers.authorization || "").replace(/^Bearer\s+/i, "");

    // Public endpoints
    if (url.pathname === "/api/health") {
      return json(res, {
        ok: true,
        data: { status: "ok", streamConnected: true, services: { agent: true, commentary: true, streamManagement: true }, timestamp: Date.now() }
      });
    }
    if (url.pathname === "/api/device/identity") {
      return json(res, {
        ok: true,
        data: { deviceId: DEVICE_ID, model: "Pixel 8", androidVersion: "15", appVersion: "2.1.0" }
      });
    }
    if (url.pathname === "/api/device/pair-requests" && req.method === "POST") {
      return readBody(req, (body) => {
        const requestId = `pair_${pairingRequests.size + 1}`;
        const request = {
          id: requestId,
          bridgeId: body.bridgeId,
          bridgeName: body.bridgeName,
          status: "Pending",
          pollCount: 0,
          createdAt: Date.now(),
          expiresAt: Date.now() + 600000
        };
        pairingRequests.set(requestId, request);
        json(res, { ok: true, data: request }, 201);
      });
    }
    const pairRequestMatch = url.pathname.match(/^\/api\/device\/pair-requests\/([^/]+)$/);
    if (pairRequestMatch && req.method === "GET") {
      const requestId = decodeURIComponent(pairRequestMatch[1]);
      const request = pairingRequests.get(requestId);
      if (!request) return json(res, { ok: false, error: "not_found", errorCode: "not_found" }, 404);
      request.pollCount += 1;
      if (request.pollCount >= 2) {
        request.status = "Approved";
        request.bindingToken = BINDING_TOKEN;
        request.deviceId = DEVICE_ID;
      }
      return json(res, { ok: true, data: request });
    }

    // Auth check
    const authKind = apiKey === API_KEY ? "api_key" : bearerToken === BINDING_TOKEN ? "binding_token" : null;
    if (!authKind) {
      return json(res, { ok: false, error: "invalid_api_key", errorCode: "invalid_api_key" }, 401);
    }

    // Authenticated endpoints
    if (url.pathname === "/api/device/pair" && req.method === "POST") {
      return readBody(req, (body) => {
        json(res, {
          ok: true,
          data: { bridgeId: body.bridgeId, bridgeName: body.bridgeName, deviceId: DEVICE_ID, bindingToken: "tok_test_123", createdAt: Date.now() }
        });
      });
    }

    if (url.pathname === "/api/capabilities") {
      return json(res, {
        ok: true,
        data: {
          service: { name: "Watcher", version: "1.1" },
          authKind,
          tools: [
            { type: "function", function: { name: "monitor", description: "Monitor task" } },
            { type: "function", function: { name: "snapshot", description: "Snapshot" } }
          ]
        }
      });
    }

    // Relay chat routes removed - now handled by ntfy directly

    if (url.pathname === "/api/stream/snapshot") {
      res.writeHead(200, { "Content-Type": "image/jpeg" });
      return res.end(Buffer.from("fake-jpeg-data"));
    }

    if (url.pathname === "/api/tasks" && req.method === "POST") {
      return readBody(req, (body) => {
        taskCounter++;
        const task = { id: `task_${taskCounter}`, tool: body.tool, status: "Running", createdAt: Date.now(), events: [] };
        tasks.set(task.id, task);
        json(res, { ok: true, data: task });
      });
    }

    if (url.pathname === "/api/tasks" && req.method === "GET") {
      return json(res, { ok: true, data: Array.from(tasks.values()) });
    }

    const taskMatch = url.pathname.match(/^\/api\/tasks\/([^/]+)$/);
    if (taskMatch) {
      const taskId = decodeURIComponent(taskMatch[1]);
      const task = tasks.get(taskId);
      if (!task) return json(res, { ok: false, error: "not_found", errorCode: "not_found" }, 404);

      if (req.method === "GET") {
        return json(res, { ok: true, data: task });
      }
      if (req.method === "DELETE") {
        task.status = "Cancelled";
        return json(res, { ok: true, data: { cancelled: true } });
      }
    }

    const eventsMatch = url.pathname.match(/^\/api\/tasks\/([^/]+)\/events$/);
    if (eventsMatch) {
      const taskId = decodeURIComponent(eventsMatch[1]);
      const task = tasks.get(taskId);
      if (!task) return json(res, { ok: false, error: "not_found", errorCode: "not_found" }, 404);

      // Simulate an ALERT event after first poll
      if (task.events.length === 0) {
        task.events.push({ id: 1, type: "check_result", data: { status: "ALERT", message: "User left desk" }, timestamp: Date.now() });
      }
      return json(res, { ok: true, data: task.events });
    }

    if (url.pathname === "/api/commentary/state") {
      return json(res, { ok: true, data: { enabled: true, speaking: false, mode: "observer" } });
    }

    if (url.pathname.startsWith("/api/commentary/entries")) {
      return json(res, { ok: true, data: [{ id: 1, text: "Test entry", timestamp: Date.now() }] });
    }

    json(res, { ok: false, error: "not_found", errorCode: "not_found" }, 404);
  });

  return server;
}

function json(res, data, status = 200) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(data));
}

function readBody(req, callback) {
  let body = "";
  req.on("data", (chunk) => body += chunk);
  req.on("end", () => callback(JSON.parse(body)));
}

// --- MCP Client ---

class McpClient {
  constructor(proc) {
    this.proc = proc;
    this.buffer = "";
    this.pending = new Map();
    this.nextId = 1;

    proc.stdout.on("data", (data) => {
      this.buffer += data.toString();
      this._processBuffer();
    });

    proc.stderr.on("data", (data) => {
      // MCP SDK may log to stderr
    });
  }

  _processBuffer() {
    const lines = this.buffer.split("\n");
    this.buffer = lines.pop();
    for (const line of lines) {
      if (!line.trim()) continue;
      try {
        const msg = JSON.parse(line);
        const resolver = this.pending.get(msg.id);
        if (resolver) {
          this.pending.delete(msg.id);
          resolver(msg);
        }
      } catch {}
    }
  }

  send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = this.nextId++;
      const msg = JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n";
      this.pending.set(id, resolve);
      this.proc.stdin.write(msg);
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reject(new Error(`Timeout for ${method}`));
        }
      }, 10000);
    });
  }

  async initialize() {
    return this.send("initialize", {
      protocolVersion: "2024-11-05",
      capabilities: {},
      clientInfo: { name: "test-client", version: "1.0.0" }
    });
  }

  async listTools() {
    return this.send("tools/list", {});
  }

  async callTool(name, args = {}) {
    return this.send("tools/call", { name, arguments: args });
  }

  close() {
    this.proc.stdin.end();
    this.proc.kill();
  }
}

// --- Test runner ---

let gateway;
let gatewayUrl;
let client;
let stateDir;
let passed = 0;
let failed = 0;

function check(label, condition, detail) {
  if (condition) {
    passed++;
    console.log(`  PASS  ${label}`);
  } else {
    failed++;
    console.log(`  FAIL  ${label}${detail ? ": " + detail : ""}`);
  }
}

async function run() {
  // Start mock gateway
  gateway = createMockGateway();
  await new Promise((resolve) => gateway.listen(0, "127.0.0.1", resolve));
  const port = gateway.address().port;
  gatewayUrl = `http://127.0.0.1:${port}`;
  stateDir = fs.mkdtempSync(path.join(os.tmpdir(), "watcher-mcp-test-"));
  console.log(`Mock gateway on ${gatewayUrl}`);

  // Start MCP server
  const proc = spawn("node", [SERVER_JS], {
    stdio: ["pipe", "pipe", "pipe"],
    env: { ...process.env, WATCHER_MCP_STATE_DIR: stateDir }
  });
  client = new McpClient(proc);

  // Wait a bit for server startup
  await new Promise((r) => setTimeout(r, 500));

  console.log("\n--- Initialize ---");
  const initResp = await client.initialize();
  check("initialize returns result", !!initResp.result);
  check("server name is watcher-mcp", initResp.result?.serverInfo?.name === "watcher-mcp");

  console.log("\n--- List Tools ---");
  const toolsResp = await client.listTools();
  const tools = toolsResp.result?.tools || [];
  check("lists 18 tools", tools.length === 18, `got ${tools.length}`);
  check("has watcher.discover_devices", tools.some((t) => t.name === "watcher.discover_devices"));
  check("has watcher.bind_device", tools.some((t) => t.name === "watcher.bind_device"));
  check("has watcher.wait_for_condition", tools.some((t) => t.name === "watcher.wait_for_condition"));
  check("has watcher.send_relay_message", tools.some((t) => t.name === "watcher.send_relay_message"));
  const bindTool = tools.find((t) => t.name === "watcher.bind_device");
  check("bind_device no longer requires apiKey", !bindTool?.inputSchema?.required?.includes("apiKey"));
  check("bind_device accepts timeoutMs", !!bindTool?.inputSchema?.properties?.timeoutMs);

  console.log("\n--- Bind Device ---");
  const bindResp = await client.callTool("watcher.bind_device", { baseUrl: gatewayUrl, apiKey: API_KEY });
  const bindData = parseToolResult(bindResp);
  check("bind returns paired", !!bindData?.paired);
  check("bind returns deviceId", bindData?.paired?.deviceId === DEVICE_ID);
  check("bind returns identity", !!bindData?.identity);
  check("bind returns health", !!bindData?.health);

  console.log("\n--- Auto Bind Device ---");
  const autoBindResp = await client.callTool("watcher.bind_device", {
    baseUrl: gatewayUrl,
    bridgeId: "claude-code",
    bridgeName: "Claude Code",
    timeoutMs: 5000,
    pollIntervalMs: 100
  });
  const autoBindData = parseToolResult(autoBindResp);
  check("auto bind returns paired", !!autoBindData?.paired);
  check("auto bind returns binding token", autoBindData?.paired?.bindingToken === BINDING_TOKEN);
  check("auto bind status is Approved", autoBindData?.paired?.status === "Approved");

  console.log("\n--- Get Device ---");
  const devResp = await client.callTool("watcher.get_device", {});
  const devData = parseToolResult(devResp);
  check("get_device returns deviceId", devData?.deviceId === DEVICE_ID);
  check("get_device returns health.status", devData?.health?.status === "ok");

  console.log("\n--- Get Capabilities ---");
  const capResp = await client.callTool("watcher.get_capabilities", {});
  const capData = parseToolResult(capResp);
  check("capabilities returns service name", capData?.capabilities?.service?.name === "Watcher");
  check("capabilities lists tools", Array.isArray(capData?.capabilities?.tools));
  check("capabilities uses binding token after auto bind", capData?.capabilities?.authKind === "binding_token");

  console.log("\n--- Capture Snapshot ---");
  const snapResp = await client.callTool("watcher.capture_snapshot", {});
  const snapData = parseToolResult(snapResp);
  check("snapshot returns mimeType", snapData?.mimeType === "image/jpeg");
  check("snapshot returns dataUrl", snapData?.dataUrl?.startsWith("data:image/jpeg;base64,"));

  console.log("\n--- Create Task ---");
  const createResp = await client.callTool("watcher.create_task", {
    tool: "monitor",
    params: { objective: "Watch for user leaving desk", checkIntervalSeconds: 5, triggerCondition: "ALERT when user leaves" }
  });
  const createData = parseToolResult(createResp);
  check("create_task returns task", !!createData?.task);
  check("create_task has id", !!createData?.task?.id);
  check("create_task status is Running", createData?.task?.status === "Running");
  const taskId = createData?.task?.id;

  console.log("\n--- List Tasks ---");
  const listResp = await client.callTool("watcher.list_tasks", {});
  const listData = parseToolResult(listResp);
  check("list_tasks returns array", Array.isArray(listData?.tasks));
  check("list_tasks includes created task", listData?.tasks?.some((t) => t.id === taskId));

  console.log("\n--- Get Task ---");
  const getResp = await client.callTool("watcher.get_task", { taskId });
  const getData = parseToolResult(getResp);
  check("get_task returns task", !!taskId && getData?.task?.id === taskId);

  console.log("\n--- List Task Events ---");
  const eventsResp = await client.callTool("watcher.list_task_events", { taskId });
  const eventsData = parseToolResult(eventsResp);
  check("list_task_events returns events", Array.isArray(eventsData?.events));
  check("events contain ALERT", eventsData?.events?.some((e) => e.type === "check_result"));

  console.log("\n--- Wait For Condition ---");
  const waitResp = await client.callTool("watcher.wait_for_condition", {
    taskId,
    eventType: "check_result",
    eventDataContains: "ALERT",
    timeoutMs: 5000,
    pollIntervalMs: 500
  });
  const waitData = parseToolResult(waitResp);
  check("wait_for_condition matched", waitData?.matched === true);
  check("wait reason is event_match", waitData?.reason === "event_match");
  check("matched event contains ALERT", JSON.stringify(waitData?.event?.data || {}).includes("ALERT"));

  console.log("\n--- Cancel Task ---");
  const cancelResp = await client.callTool("watcher.cancel_task", { taskId });
  const cancelData = parseToolResult(cancelResp);
  check("cancel_task returns result", !!cancelData?.result);

  console.log("\n--- Commentary State ---");
  const commStateResp = await client.callTool("watcher.get_commentary_state", {});
  const commStateData = parseToolResult(commStateResp);
  check("commentary state returns enabled", commStateData?.state?.enabled === true);

  console.log("\n--- Commentary Entries ---");
  const commEntriesResp = await client.callTool("watcher.list_commentary_entries", {});
  const commEntriesData = parseToolResult(commEntriesResp);
  check("commentary entries returns array", Array.isArray(commEntriesData?.entries));
  check("commentary entries has content", commEntriesData?.entries?.length > 0);

  console.log("\n--- Relay Chat (ntfy) ---");
  const ntfyClient = await import(pathToFileURL(NTFY_CLIENT_JS).href);
  const relayTools = tools.filter((t) => t.name.includes("relay"));
  check("relay tools include get_relay_messages", relayTools.some((t) => t.name === "watcher.get_relay_messages"));
  check("relay tools include send_relay_message", relayTools.some((t) => t.name === "watcher.send_relay_message"));
  check("get_relay_messages requires conversationId", tools.find((t) => t.name === "watcher.get_relay_messages")?.inputSchema?.required?.includes("conversationId"));
  check("send_relay_message requires content", tools.find((t) => t.name === "watcher.send_relay_message")?.inputSchema?.required?.includes("content"));
  check("ntfy client has no default server", ntfyClient.DEFAULT_NTFY_SERVER === "");
  check("ntfy client has no default auth token", ntfyClient.DEFAULT_NTFY_AUTH_TOKEN == null);

  const missingRelayResp = await client.callTool("watcher.check_phone_available", { topic: "watcher-test" });
  const missingRelayData = parseToolResult(missingRelayResp);
  check("check_phone_available requires explicit ntfy server", missingRelayResp?.result?.isError === true);
  check("missing ntfy server error is explicit", missingRelayData?.error === "ntfyServerUrl is required.");

  const httpRelayResp = await client.callTool("watcher.check_phone_available", {
    ntfyServerUrl: "http://ntfy.example.test",
    topic: "watcher-test"
  });
  const httpRelayData = parseToolResult(httpRelayResp);
  check("check_phone_available accepts explicit http relay config", httpRelayResp?.result?.isError !== true);
  check("explicit http relay config reaches network check", httpRelayData?.available === false && !!httpRelayData?.error);

  const httpsRelayResp = await client.callTool("watcher.check_phone_available", {
    ntfyServerUrl: "https://127.0.0.1:1",
    topic: "watcher-test",
    authToken: "explicit-token"
  });
  const httpsRelayData = parseToolResult(httpsRelayResp);
  check("check_phone_available accepts explicit https relay config", httpsRelayResp?.result?.isError !== true);
  check("explicit https relay config reaches network check", httpsRelayData?.available === false && !!httpsRelayData?.error);

  // Summary
  console.log(`\n${"=".repeat(40)}`);
  console.log(`Results: ${passed} passed, ${failed} failed, ${passed + failed} total`);
  console.log(`${"=".repeat(40)}\n`);

  client.close();
  gateway.close();
  fs.rmSync(stateDir, { recursive: true, force: true });

  process.exit(failed > 0 ? 1 : 0);
}

function parseToolResult(resp) {
  try {
    const text = resp?.result?.content?.[0]?.text;
    return text ? JSON.parse(text) : null;
  } catch {
    return null;
  }
}

run().catch((err) => {
  console.error("Test runner error:", err);
  if (client) client.close();
  if (gateway) gateway.close();
  if (stateDir) fs.rmSync(stateDir, { recursive: true, force: true });
  process.exit(1);
});
