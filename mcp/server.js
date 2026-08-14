#!/usr/bin/env node
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema
} from "@modelcontextprotocol/sdk/types.js";
import {
  fetchCapabilities,
  fetchCommentaryEntries,
  fetchCommentaryState,
  fetchHealth,
  fetchIdentity,
  fetchTask,
  fetchTaskEvents,
  fetchTasks,
  pairDevice,
  createPairingRequest,
  fetchPairingRequest,
  createTask,
  cancelTask,
  fetchSnapshot
} from "./lib/gateway-client.js";
import { ntfyPublish, ntfyPoll, DEFAULT_NTFY_SERVER, DEFAULT_NTFY_TOPIC, validateNtfyServerUrl } from "./lib/ntfy-client.js";
import { RelayInbox } from "./lib/inbox.js";
import { discoverDevices } from "./lib/discovery.js";

const inbox = new RelayInbox();

function generateMessageId() {
  return "msg_" + globalThis.crypto.randomUUID().replace(/-/g, "").slice(0, 12);
}

function getNextTurnId(conversationId) {
  const all = inbox.data.messages.filter((m) => m.conversationId === conversationId);
  const maxTurn = all.reduce((max, m) => Math.max(max, m.turnId || 0), 0);
  return maxTurn + 1;
}
import { loadDevices, saveDevices, loadRelayConfig, saveRelayConfig } from "./lib/state.js";

const toolDefinitions = [
  // ── Relay tools (ntfy-based, work anywhere, no LAN/binding required) ──
  // These are the PRIMARY communication tools for cross-device conversation handoff.
  // They use a cloud relay server and work regardless of network topology.

  {
    name: "watcher.handoff_conversation",
    description: `Hand off the current conversation to the user's phone for cross-device continuation.

WORKFLOW: handoff_conversation → wait_for_relay_reply (blocks until reply) → send_relay_message → detect hand_back → resume on PC.

Use this when: (1) the user says they're leaving the computer, (2) the user asks to continue on mobile, or (3) a task needs the user's physical-world input (e.g. checking something in person).

This tool FIRST checks if the phone is online (via presence heartbeat). If the phone is offline, it returns phoneAvailable=false — in that case, ask the user to open Watcher app on their phone and enable the availability toggle in the multi-device section.

After a successful handoff, call watcher.wait_for_relay_reply to block until the phone user responds. A reply with type='hand_back' means the phone user is done and you can resume control. Use watcher.get_relay_messages if you need to view full conversation history.

You may reuse the same conversationId to re-activate a previously handed-back conversation with updated context.

[Category: Relay — no LAN or device binding needed]`,
    inputSchema: {
      type: "object",
      required: ["title", "summary"],
      properties: {
        title: { type: "string", description: "Short conversation title shown on phone notification and chat list (e.g. '讨论部署方案')" },
        summary: { type: "string", description: "Full context summary of the conversation so far — the phone user reads this to understand where things left off" },
        content: { type: "string", description: "Specific instruction or question for the phone user to act on" },
        conversationId: { type: "string", description: "Optional. Auto-generated if omitted. Reuse the same ID to continue an existing handoff conversation." }
      }
    }
  },
  {
    name: "watcher.get_relay_messages",
    description: `Read the full message history of a phone relay conversation. Use this to review what was said, or to check conversation status.

Each message has: type ('message' = normal reply, 'hand_back' = phone user finished and returned control), author ('phone_user' or 'pc_agent'), and content.

Returns handedBack=true when the phone user has handed control back to you. For waiting on new replies, prefer watcher.wait_for_relay_reply which blocks until a reply arrives.

[Category: Relay — no LAN or device binding needed]`,
    inputSchema: {
      type: "object",
      required: ["conversationId"],
      properties: {
        conversationId: { type: "string", description: "The conversation ID returned by watcher.handoff_conversation" },
        since: { type: "string", default: "1h", description: "Time window for history (e.g. 5m, 30m, 1h, 12h)" }
      }
    }
  },
  {
    name: "watcher.wait_for_relay_reply",
    description: `Block and wait for the phone user to reply in a relay conversation. Returns immediately when a phone_user message or hand_back arrives — no polling needed.

Use this after watcher.handoff_conversation when you want to be notified the instant the user responds. If you'd rather do other work while waiting, use watcher.get_relay_messages to poll instead.

Default timeout is 5 minutes. On timeout, returns timedOut=true — you can retry or ask the user if they'd like more time.

[Category: Relay — no LAN or device binding needed]`,
    inputSchema: {
      type: "object",
      required: ["conversationId"],
      properties: {
        conversationId: { type: "string", description: "The conversation ID to watch for replies" },
        timeoutMs: { type: "integer", default: 300000, description: "Max wait time in ms (default: 5 minutes)" }
      }
    }
  },
  {
    name: "watcher.check_phone_available",
    description: `Check if the user's phone is online and available for conversation handoff.

Use this BEFORE calling watcher.handoff_conversation to decide whether a handoff is feasible. Returns available=true if the phone has sent a recent presence heartbeat (within 5 minutes).

If the phone is not available, you can inform the user and suggest they open the Watcher app and enable the availability toggle — or choose an alternative strategy (e.g. leave a note for later).

If this is the first time connecting, pass the device topic (user can copy it from Watcher app settings). It will be stored and reused automatically for all future calls.

[Category: Relay — no LAN or device binding needed]`,
    inputSchema: {
      type: "object",
      properties: {
        topic: { type: "string", description: "The device topic from Watcher app settings (e.g. 'watcher-fb02ec34'). Only needed on first use — stored automatically after." },
        authToken: { type: "string", description: "The ntfy auth token. Only needed on first use — user can copy it from Watcher app settings along with the topic." }
      }
    }
  },
  {
    name: "watcher.send_relay_message",
    description: `Send a follow-up message to an active phone conversation. The phone user receives it in real-time.

Use this to reply within an ongoing handoff conversation. Do NOT use this to start a new handoff — use watcher.handoff_conversation instead (it carries context and triggers a notification).

[Category: Relay — no LAN or device binding needed]`,
    inputSchema: {
      type: "object",
      required: ["conversationId", "content"],
      properties: {
        conversationId: { type: "string", description: "The conversation ID from watcher.handoff_conversation" },
        content: { type: "string", description: "Message text to send to the phone user" }
      }
    }
  },

  // ── Gateway tools (LAN-only, require discover + bind_device first) ──
  // These provide direct device control (camera, tasks, monitoring) over LAN HTTP.
  // PREREQUISITE: Call watcher.discover_devices then watcher.bind_device before using these.

  {
    name: "watcher.discover_devices",
    description: "Discover Watcher devices on the current LAN via mDNS. Returns a list of reachable devices with connection details. [Category: Gateway — requires LAN connectivity]",
    inputSchema: {
      type: "object",
      properties: {
        timeoutMs: { type: "integer", default: 2500 }
      }
    }
  },
  {
    name: "watcher.bind_device",
    description: "Bind one Watcher device using an API key or a phone-approved first-use pairing request. Required before using any other Gateway tools (capture_snapshot, create_task, etc.). [Category: Gateway — requires LAN connectivity]",
    inputSchema: {
      type: "object",
      properties: {
        baseUrl: { type: "string" },
        apiKey: { type: "string" },
        bridgeId: { type: "string", default: "watcher-mcp" },
        bridgeName: { type: "string", default: "Watcher MCP" },
        timeoutMs: { type: "integer", default: 120000 },
        pollIntervalMs: { type: "integer", default: 1500 }
      }
    }
  },
  {
    name: "watcher.get_device",
    description: "Read one bound device's identity and health. [Category: Gateway — requires LAN + bind_device]",
    inputSchema: {
      type: "object",
      properties: {
        deviceId: { type: "string" },
        baseUrl: { type: "string" }
      }
    }
  },
  {
    name: "watcher.get_capabilities",
    description: "Read the Watcher gateway capabilities contract. [Category: Gateway — requires LAN + bind_device]",
    inputSchema: {
      type: "object",
      properties: {
        deviceId: { type: "string" },
        baseUrl: { type: "string" }
      }
    }
  },
  {
    name: "watcher.capture_snapshot",
    description: "Capture the current device camera frame as a base64 JPEG payload. [Category: Gateway — requires LAN + bind_device]",
    inputSchema: {
      type: "object",
      properties: {
        deviceId: { type: "string" },
        baseUrl: { type: "string" }
      }
    }
  },
  {
    name: "watcher.create_task",
    description: "Create a Watcher task such as snapshot, monitor, or video_analysis. [Category: Gateway — requires LAN + bind_device]",
    inputSchema: {
      type: "object",
      required: ["tool"],
      properties: {
        deviceId: { type: "string" },
        baseUrl: { type: "string" },
        tool: { type: "string" },
        params: { type: "object", additionalProperties: true }
      }
    }
  },
  {
    name: "watcher.list_tasks",
    description: "List recent tasks on a Watcher device. [Category: Gateway — requires LAN + bind_device]",
    inputSchema: {
      type: "object",
      properties: {
        deviceId: { type: "string" },
        baseUrl: { type: "string" }
      }
    }
  },
  {
    name: "watcher.get_task",
    description: "Read one task snapshot by id. [Category: Gateway — requires LAN + bind_device]",
    inputSchema: {
      type: "object",
      required: ["taskId"],
      properties: {
        deviceId: { type: "string" },
        baseUrl: { type: "string" },
        taskId: { type: "string" }
      }
    }
  },
  {
    name: "watcher.list_task_events",
    description: "List task events incrementally. [Category: Gateway — requires LAN + bind_device]",
    inputSchema: {
      type: "object",
      required: ["taskId"],
      properties: {
        deviceId: { type: "string" },
        baseUrl: { type: "string" },
        taskId: { type: "string" },
        afterEventId: { type: "integer" },
        since: { type: "integer" }
      }
    }
  },
  {
    name: "watcher.wait_for_condition",
    description: "Poll a task until a matching event or terminal state is observed. [Category: Gateway — requires LAN + bind_device]",
    inputSchema: {
      type: "object",
      required: ["taskId"],
      properties: {
        deviceId: { type: "string" },
        baseUrl: { type: "string" },
        taskId: { type: "string" },
        eventType: { type: "string" },
        eventDataContains: { type: "string" },
        timeoutMs: { type: "integer", default: 120000 },
        pollIntervalMs: { type: "integer", default: 3000 },
        returnOnTerminal: { type: "boolean", default: true }
      }
    }
  },
  {
    name: "watcher.cancel_task",
    description: "Cancel a running Watcher task. [Category: Gateway — requires LAN + bind_device]",
    inputSchema: {
      type: "object",
      required: ["taskId"],
      properties: {
        deviceId: { type: "string" },
        baseUrl: { type: "string" },
        taskId: { type: "string" }
      }
    }
  },
  {
    name: "watcher.get_commentary_state",
    description: "Read current commentary and speech state. [Category: Gateway — requires LAN + bind_device]",
    inputSchema: {
      type: "object",
      properties: {
        deviceId: { type: "string" },
        baseUrl: { type: "string" }
      }
    }
  },
  {
    name: "watcher.list_commentary_entries",
    description: "Read commentary entries, optionally incrementally. [Category: Gateway — requires LAN + bind_device]",
    inputSchema: {
      type: "object",
      properties: {
        deviceId: { type: "string" },
        baseUrl: { type: "string" },
        since: { type: "integer" }
      }
    }
  }
];

function asToolResult(payload) {
  return {
    content: [
      {
        type: "text",
        text: JSON.stringify(payload, null, 2)
      }
    ]
  };
}

function getStoredDevice(args = {}) {
  const devices = loadDevices();
  if (args.baseUrl) {
    return devices.find((entry) => entry.baseUrl === args.baseUrl) || null;
  }
  if (args.deviceId) {
    return devices.find((entry) => entry.deviceId === args.deviceId) || null;
  }
  return devices[devices.length - 1] || null;
}

function requireDevice(args = {}) {
  const device = getStoredDevice(args);
  if (!device) {
    throw new Error("No bound Watcher device found. Run watcher.bind_device first.");
  }
  return device;
}

function withDevice(args = {}) {
  const device = requireDevice(args);
  const bindingToken = device.bindingToken;
  return {
    device,
    baseUrl: args.baseUrl || device.baseUrl,
    apiKey: bindingToken ? undefined : device.apiKey,
    bindingToken
  };
}

async function resolveBaseUrl(args = {}) {
  if (args.baseUrl) return args.baseUrl;
  const discovered = await discoverDevices({ timeoutMs: args.discoveryTimeoutMs || 2500 });
  const first = discovered.devices?.[0];
  if (!first?.baseUrl) {
    throw new Error("No Watcher device discovered. Pass baseUrl or make sure the phone Gateway is running on the same LAN.");
  }
  return first.baseUrl;
}

async function handleDiscover(args) {
  return discoverDevices({ timeoutMs: args.timeoutMs || 2500 });
}

async function waitForPairingApproval({ baseUrl, requestId, timeoutMs, pollIntervalMs }) {
  const deadline = Date.now() + timeoutMs;
  let latest = null;
  while (Date.now() < deadline) {
    latest = await fetchPairingRequest({ baseUrl, requestId });
    if (latest.status === "Approved" && latest.bindingToken) {
      return latest;
    }
    if (latest.status === "Rejected" || latest.status === "Expired") {
      throw new Error(`Pairing request ${latest.status.toLowerCase()}`);
    }
    await new Promise((resolve) => setTimeout(resolve, pollIntervalMs));
  }
  throw new Error(`Pairing request timed out${latest?.status ? ` with status ${latest.status}` : ""}`);
}

async function handleBind(args) {
  const baseUrl = await resolveBaseUrl(args);
  const bridgeId = args.bridgeId || "watcher-mcp";
  const bridgeName = args.bridgeName || "Watcher MCP";
  let pairing;

  if (args.apiKey) {
    pairing = await pairDevice({
      baseUrl,
      apiKey: args.apiKey,
      bridgeId,
      bridgeName
    });
  } else {
    try {
      const request = await createPairingRequest({
        baseUrl,
        bridgeId,
        bridgeName
      });
      pairing = await waitForPairingApproval({
        baseUrl,
        requestId: request.id,
        timeoutMs: args.timeoutMs || 120000,
        pollIntervalMs: args.pollIntervalMs || 1500
      });
    } catch (err) {
      if (err.status === 404 || err.status === 405) {
        throw new Error(
          "Phone-approved pairing is not supported by this Watcher gateway. Update the Watcher app or pass apiKey to watcher.bind_device."
        );
      }
      throw err;
    }
  }

  const bindingToken = pairing.bindingToken;
  const [identity, health] = await Promise.all([
    fetchIdentity(baseUrl),
    fetchHealth(baseUrl)
  ]);
  const devices = loadDevices().filter((entry) => entry.deviceId !== pairing.deviceId && entry.baseUrl !== baseUrl);
  devices.push({
    bridgeId: pairing.bridgeId,
    bridgeName: pairing.bridgeName,
    deviceId: pairing.deviceId,
    bindingToken,
    baseUrl,
    apiKey: args.apiKey,
    identity,
    health,
    pairedAt: pairing.createdAt
  });
  saveDevices(devices);
  return {
    paired: pairing,
    identity,
    health,
    storedDeviceCount: devices.length
  };
}

async function handleGetDevice(args) {
  const { device, baseUrl } = withDevice(args);
  const [identity, health] = await Promise.all([
    fetchIdentity(baseUrl),
    fetchHealth(baseUrl)
  ]);
  const updatedDevices = loadDevices().map((entry) =>
    entry.deviceId === device.deviceId ? { ...entry, identity, health, baseUrl } : entry
  );
  saveDevices(updatedDevices);
  return {
    deviceId: device.deviceId,
    baseUrl,
    identity,
    health
  };
}

async function handleCapabilities(args) {
  const { device, baseUrl, apiKey, bindingToken } = withDevice(args);
  const capabilities = await fetchCapabilities({ baseUrl, apiKey, bindingToken });
  return {
    deviceId: device.deviceId,
    baseUrl,
    capabilities
  };
}

async function handleSnapshot(args) {
  const { device, baseUrl, apiKey, bindingToken } = withDevice(args);
  const snapshot = await fetchSnapshot({ baseUrl, apiKey, bindingToken });
  return {
    deviceId: device.deviceId,
    baseUrl,
    ...snapshot
  };
}

async function handleCreateTask(args) {
  const { device, baseUrl, apiKey, bindingToken } = withDevice(args);
  const params = args.params && typeof args.params === "object" ? args.params : {};
  const task = await createTask({
    baseUrl,
    apiKey,
    bindingToken,
    payload: {
      tool: args.tool,
      ...params
    }
  });
  return {
    deviceId: device.deviceId,
    baseUrl,
    task
  };
}

async function handleListTasks(args) {
  const { device, baseUrl, apiKey, bindingToken } = withDevice(args);
  const tasks = await fetchTasks({ baseUrl, apiKey, bindingToken });
  return {
    deviceId: device.deviceId,
    baseUrl,
    tasks
  };
}

async function handleGetTask(args) {
  const { device, baseUrl, apiKey, bindingToken } = withDevice(args);
  const task = await fetchTask({ baseUrl, apiKey, bindingToken, taskId: args.taskId });
  return {
    deviceId: device.deviceId,
    baseUrl,
    task
  };
}

async function handleListTaskEvents(args) {
  const { device, baseUrl, apiKey, bindingToken } = withDevice(args);
  const events = await fetchTaskEvents({
    baseUrl,
    apiKey,
    bindingToken,
    taskId: args.taskId,
    afterEventId: args.afterEventId,
    since: args.since
  });
  return {
    deviceId: device.deviceId,
    baseUrl,
    taskId: args.taskId,
    events
  };
}

function eventMatches(event, args) {
  if (args.eventType && event.type !== args.eventType) {
    return false;
  }
  if (args.eventDataContains) {
    const haystack = JSON.stringify(event.data ?? event.payload ?? {});
    if (!haystack.includes(args.eventDataContains)) {
      return false;
    }
  }
  return true;
}

async function handleWaitForCondition(args) {
  const { device, baseUrl, apiKey, bindingToken } = withDevice(args);
  const timeoutMs = args.timeoutMs || 120000;
  const pollIntervalMs = args.pollIntervalMs || 3000;
  const returnOnTerminal = args.returnOnTerminal !== false;
  const deadline = Date.now() + timeoutMs;
  let afterEventId = 0;

  while (Date.now() < deadline) {
    const events = await fetchTaskEvents({
      baseUrl,
      apiKey,
      bindingToken,
      taskId: args.taskId,
      afterEventId
    });
    if (events.length > 0) {
      afterEventId = Math.max(...events.map((entry) => entry.id || 0), afterEventId);
      const matched = events.find((event) => eventMatches(event, args));
      if (matched) {
        return {
          deviceId: device.deviceId,
          baseUrl,
          taskId: args.taskId,
          matched: true,
          reason: "event_match",
          event: matched
        };
      }
    }

    const task = await fetchTask({ baseUrl, apiKey, bindingToken, taskId: args.taskId });
    if (returnOnTerminal && ["Completed", "Failed", "Cancelled"].includes(task.status)) {
      return {
        deviceId: device.deviceId,
        baseUrl,
        taskId: args.taskId,
        matched: false,
        reason: "task_terminal",
        task
      };
    }

    await new Promise((resolve) => setTimeout(resolve, pollIntervalMs));
  }

  const finalTask = await fetchTask({ baseUrl, apiKey, bindingToken, taskId: args.taskId });
  return {
    deviceId: device.deviceId,
    baseUrl,
    taskId: args.taskId,
    matched: false,
    reason: "timeout",
    task: finalTask
  };
}

async function handleCancelTask(args) {
  const { device, baseUrl, apiKey, bindingToken } = withDevice(args);
  const result = await cancelTask({ baseUrl, apiKey, bindingToken, taskId: args.taskId });
  return {
    deviceId: device.deviceId,
    baseUrl,
    taskId: args.taskId,
    result
  };
}

async function handleCommentaryState(args) {
  const { device, baseUrl, apiKey, bindingToken } = withDevice(args);
  const state = await fetchCommentaryState({ baseUrl, apiKey, bindingToken });
  return {
    deviceId: device.deviceId,
    baseUrl,
    state
  };
}

async function handleCommentaryEntries(args) {
  const { device, baseUrl, apiKey, bindingToken } = withDevice(args);
  const entries = await fetchCommentaryEntries({ baseUrl, apiKey, bindingToken, since: args.since });
  return {
    deviceId: device.deviceId,
    baseUrl,
    entries
  };
}

function resolveNtfyConfig(args) {
  const device = getStoredDevice(args);
  const relayConfig = loadRelayConfig();
  const topic = args.ntfyTopic || relayConfig.topic || device?.ntfyTopic || DEFAULT_NTFY_TOPIC;
  if (!topic) {
    throw new Error("ntfy topic not configured. Ask the user for their device topic (shown in Watcher app settings) and call watcher.check_phone_available with it.");
  }
  const serverUrl = args.ntfyServerUrl || relayConfig.serverUrl || device?.ntfyServerUrl || DEFAULT_NTFY_SERVER;
  const serverError = validateNtfyServerUrl(serverUrl);
  if (serverError) {
    throw new Error(serverError);
  }
  return {
    serverUrl,
    topic,
    authToken: args.ntfyAuthToken || relayConfig.authToken || device?.ntfyAuthToken || null
  };
}

function storeRelayConfig(ntfyConfig) {
  const stored = loadRelayConfig();
  let changed = false;
  if (ntfyConfig.topic && stored.topic !== ntfyConfig.topic) {
    stored.topic = ntfyConfig.topic;
    changed = true;
  }
  if (ntfyConfig.authToken && stored.authToken !== ntfyConfig.authToken) {
    stored.authToken = ntfyConfig.authToken;
    changed = true;
  }
  if (ntfyConfig.serverUrl && stored.serverUrl !== ntfyConfig.serverUrl) {
    stored.serverUrl = ntfyConfig.serverUrl;
    changed = true;
  }
  if (changed) {
    stored.updatedAt = Date.now();
    saveRelayConfig(stored);
  }
}

async function handleCheckPhoneAvailable(args) {
  // If user provides config, inject into args for resolveNtfyConfig
  if (args.topic) args.ntfyTopic = args.topic;
  if (args.authToken) args.ntfyAuthToken = args.authToken;
  const ntfy = resolveNtfyConfig(args);
  if (!ntfy.serverUrl) {
    throw new Error("ntfyServerUrl is required.");
  }
  // Persist resolved config for future use
  storeRelayConfig(ntfy);
  try {
    const recentMessages = await ntfyPoll(ntfy.serverUrl, ntfy.topic, {
      since: "5m",
      authToken: ntfy.authToken
    });
    const presenceMessages = recentMessages
      .filter((m) => m.type === "presence" && m.author === "phone_user")
      .sort((a, b) => (b.ts || 0) - (a.ts || 0));
    const latestPresence = presenceMessages[0];
    const available = !!(latestPresence && latestPresence.status === "available");
    return {
      available,
      lastSeenAt: latestPresence?.ts || null,
      hint: available
        ? "Phone is online. You can proceed with watcher.handoff_conversation, then use watcher.get_relay_messages to poll for replies."
        : "Phone is not available. Ask the user to open Watcher app and enable the availability toggle, or choose an alternative approach."
    };
  } catch (err) {
    return {
      available: false,
      error: err.message,
      hint: "Could not check phone availability (network error). You may still attempt watcher.handoff_conversation — it will re-check internally."
    };
  }
}

async function handleHandoffConversation(args) {
  const ntfy = resolveNtfyConfig(args);
  if (!ntfy.serverUrl) {
    throw new Error("ntfyServerUrl is required. Pass it directly or configure it during watcher.bind_device.");
  }

  // Check phone availability — look for recent presence message
  let presenceCheckFailed = false;
  try {
    const recentMessages = await ntfyPoll(ntfy.serverUrl, ntfy.topic, {
      since: "5m",
      authToken: ntfy.authToken
    });
    const presenceMessages = recentMessages
      .filter((m) => m.type === "presence" && m.author === "phone_user")
      .sort((a, b) => (b.ts || 0) - (a.ts || 0));
    const latestPresence = presenceMessages[0];
    if (!latestPresence || latestPresence.status !== "available") {
      return {
        handedOff: false,
        phoneAvailable: false,
        hint: "Phone is not available for handoff. The user has not enabled the availability toggle on their phone. Ask the user to open Watcher app and turn on the availability switch in the multi-device section."
      };
    }
  } catch (err) {
    // Presence check failed — proceed but mark it
    presenceCheckFailed = true;
    process.stderr.write(`[watcher-mcp] presence check failed: ${err.message}\n`);
  }

  const conversationId = args.conversationId || `sess_${Date.now()}`;
  const messageId = generateMessageId();
  const turnId = getNextTurnId(conversationId);
  const now = Date.now();
  const payload = {
    schema: "relay.v1",
    type: "handoff",
    messageId,
    conversationId,
    turnId,
    author: "pc_agent",
    content: args.content || "",
    title: args.title,
    summary: args.summary,
    createdAt: new Date(now).toISOString(),
    ts: now
  };
  await ntfyPublish(ntfy.serverUrl, ntfy.topic, payload, ntfy.authToken, {
    "X-Priority": "high",
    "X-Tags": "incoming_envelope"
  });
  inbox.ingest([{ ...payload, _ntfyId: messageId, _ntfyTime: Math.floor(now / 1000) }], conversationId);
  return {
    conversationId,
    handedOff: true,
    phoneAvailable: true,
    presenceCheckFailed,
    hint: presenceCheckFailed
      ? "Handoff published but presence check failed (network issue). The phone may or may not be available. Call watcher.wait_for_relay_reply to wait for a response."
      : "Handoff delivered. The phone user will see a notification. Call watcher.wait_for_relay_reply to block until they respond. A reply with type='hand_back' means they're done and you can resume."
  };
}

async function handleGetRelayMessages(args) {
  const ntfy = resolveNtfyConfig(args);
  if (!ntfy.serverUrl) {
    throw new Error("ntfyServerUrl is required. Pass it directly or configure it during watcher.bind_device.");
  }
  // Refresh inbox from ntfy
  try {
    const fetched = await ntfyPoll(ntfy.serverUrl, ntfy.topic, {
      since: args.since || "1h",
      authToken: ntfy.authToken,
      conversationId: args.conversationId
    });
    inbox.ingest(fetched, args.conversationId);
  } catch { /* poll failed — still return what's in inbox */ }

  // Read from inbox (complete history, not just unconsumed)
  const messages = inbox.getAll(args.conversationId);
  const handBackMsg = messages.find((m) => m.type === "hand_back" && m.author === "phone_user");
  const phoneReplies = messages.filter((m) => m.author === "phone_user");
  return {
    conversationId: args.conversationId,
    messages,
    count: messages.length,
    phoneReplyCount: phoneReplies.length,
    handedBack: !!handBackMsg,
    handBackSummary: handBackMsg?.content || null,
    hint: handBackMsg
      ? "The phone user has finished and handed back control. You can now resume the conversation with full context from their replies."
      : phoneReplies.length > 0
        ? "Phone user has replied. You can respond with watcher.send_relay_message, or call watcher.get_relay_messages again later to check for more replies or hand_back."
        : "No phone replies yet. The user may still be reading the context. Wait 30-60 seconds and call watcher.get_relay_messages again."
  };
}

async function handleWaitForRelayReply(args) {
  const ntfy = resolveNtfyConfig(args);
  if (!ntfy.serverUrl) {
    throw new Error("ntfyServerUrl is required.");
  }
  const timeoutMs = args.timeoutMs || 300000;
  const pollIntervalMs = 3000;
  const conversationId = args.conversationId;
  const startTime = Date.now();

  // 1. Check inbox for already-received unconsumed messages
  const existing = inbox.getUnconsumed(conversationId);
  if (existing.length > 0) {
    const msg = existing[0];
    inbox.markConsumed(msg.id);
    process.stderr.write(`[wait] found in inbox immediately: ${msg.type}\n`);
    return {
      conversationId,
      received: true,
      message: msg,
      isHandBack: msg.type === "hand_back",
      hint: msg.type === "hand_back"
        ? "Phone user is done. You can now resume the conversation with their input."
        : "Phone user replied. You can respond with watcher.send_relay_message or call watcher.wait_for_relay_reply again for the next message."
    };
  }

  // 2. Poll loop: fetch from ntfy → ingest to inbox → check for unconsumed
  let pollAttempt = 0;
  while (Date.now() - startTime < timeoutMs) {
    await new Promise((resolve) => setTimeout(resolve, pollIntervalMs));
    pollAttempt++;
    try {
      const since = inbox.getSince(conversationId);
      const messages = await ntfyPoll(ntfy.serverUrl, ntfy.topic, {
        since,
        authToken: ntfy.authToken,
        conversationId
      });
      inbox.ingest(messages, conversationId);
      process.stderr.write(`[wait] poll #${pollAttempt}: fetched ${messages.length}, since=${since}\n`);

      const unconsumed = inbox.getUnconsumed(conversationId);
      if (unconsumed.length > 0) {
        const msg = unconsumed[0];
        inbox.markConsumed(msg.id);
        process.stderr.write(`[wait] consumed: ${msg.type} id=${msg.id}\n`);
        return {
          conversationId,
          received: true,
          message: msg,
          isHandBack: msg.type === "hand_back",
          hint: msg.type === "hand_back"
            ? "Phone user is done. You can now resume the conversation with their input."
            : "Phone user replied. You can respond with watcher.send_relay_message or call watcher.wait_for_relay_reply again for the next message."
        };
      }
    } catch (err) {
      process.stderr.write(`[wait] poll #${pollAttempt} FAILED: ${err.message}\n`);
    }
  }

  return {
    conversationId,
    received: false,
    timedOut: true,
    hint: "Timed out waiting for phone reply. The user may be busy. You can try again or ask the user if they'd like you to wait longer."
  };
}

async function handleSendRelayMessage(args) {
  const ntfy = resolveNtfyConfig(args);
  if (!ntfy.serverUrl) {
    throw new Error("ntfyServerUrl is required. Pass it directly or configure it during watcher.bind_device.");
  }
  const messageId = generateMessageId();
  const turnId = getNextTurnId(args.conversationId);
  const now = Date.now();
  const payload = {
    schema: "relay.v1",
    type: "message",
    messageId,
    conversationId: args.conversationId,
    turnId,
    author: "pc_agent",
    content: args.content,
    createdAt: new Date(now).toISOString(),
    ts: now
  };
  await ntfyPublish(ntfy.serverUrl, ntfy.topic, payload, ntfy.authToken);
  inbox.ingest([{ ...payload, _ntfyId: messageId, _ntfyTime: Math.floor(now / 1000) }], args.conversationId);
  return {
    conversationId: args.conversationId,
    sent: true,
    hint: "Message published to relay. Call watcher.wait_for_relay_reply to wait for the phone user's response."
  };
}

const handlers = new Map([
  ["watcher.discover_devices", handleDiscover],
  ["watcher.bind_device", handleBind],
  ["watcher.get_device", handleGetDevice],
  ["watcher.get_capabilities", handleCapabilities],
  ["watcher.capture_snapshot", handleSnapshot],
  ["watcher.create_task", handleCreateTask],
  ["watcher.list_tasks", handleListTasks],
  ["watcher.get_task", handleGetTask],
  ["watcher.list_task_events", handleListTaskEvents],
  ["watcher.wait_for_condition", handleWaitForCondition],
  ["watcher.cancel_task", handleCancelTask],
  ["watcher.get_commentary_state", handleCommentaryState],
  ["watcher.list_commentary_entries", handleCommentaryEntries],
  ["watcher.check_phone_available", handleCheckPhoneAvailable],
  ["watcher.handoff_conversation", handleHandoffConversation],
  ["watcher.get_relay_messages", handleGetRelayMessages],
  ["watcher.wait_for_relay_reply", handleWaitForRelayReply],
  ["watcher.send_relay_message", handleSendRelayMessage]
]);

const server = new Server(
  {
    name: "watcher-mcp",
    version: "0.7.0",
    instructions: `You have access to the Watcher MCP server, which connects you to the user's Android phone running the Watcher app.

## Two categories of tools:

### Relay tools (work anywhere, no setup needed)
Cross-device conversation handoff via ntfy pub/sub. Use these when:
- The user says they're leaving the computer or wants to continue on mobile
- A task needs the user's physical-world input
- You want to send information to the user's phone

WORKFLOW: check_phone_available → handoff_conversation → wait_for_relay_reply (blocks until reply) → send_relay_message → detect hand_back → resume

PREREQUISITE: The user must have Watcher app open on their phone with the availability toggle enabled. If check_phone_available returns false, ask them to do this.

FIRST-TIME SETUP: On first use, call check_phone_available with the device topic (user copies it from Watcher app → multi-device settings → "复制配置" button). This is stored automatically and never needed again.

### Gateway tools (require LAN + device binding)
Direct device control: camera capture, monitoring tasks, video analysis. Use these when:
- You need to see what the camera sees
- You need to start a monitoring or video analysis task

WORKFLOW: discover_devices → bind_device (one-time) → then use capture_snapshot, create_task, etc.

PREREQUISITE: PC and phone must be on the same local network. Call discover_devices first.`
  },
  {
    capabilities: {
      tools: {}
    }
  }
);

server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: toolDefinitions
}));

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const name = request.params.name;
  const args = request.params.arguments || {};
  const handler = handlers.get(name);
  if (!handler) {
    return {
      content: [{ type: "text", text: JSON.stringify({ error: `Unknown tool: ${name}` }) }],
      isError: true
    };
  }
  try {
    return asToolResult(await handler(args));
  } catch (err) {
    const detail = {
      error: err.message || String(err),
      status: err.status || null,
      tool: name
    };
    if (err.cause?.code === "UND_ERR_CONNECT_TIMEOUT" || err.name === "TimeoutError") {
      detail.hint = name.includes("relay") || name.includes("handoff") || name.includes("phone")
        ? "ntfy server request timed out. Check network connectivity to the ntfy relay server."
        : "Device unreachable. Check network connectivity and that the Watcher gateway is running.";
    }
    return {
      content: [{ type: "text", text: JSON.stringify(detail, null, 2) }],
      isError: true
    };
  }
});

const transport = new StdioServerTransport();
await server.connect(transport);
