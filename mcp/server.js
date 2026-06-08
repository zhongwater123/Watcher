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
  fetchRelayConversations,
  fetchRelayMessages,
  fetchHealth,
  fetchIdentity,
  fetchTask,
  fetchTaskEvents,
  fetchTasks,
  pairDevice,
  createPairingRequest,
  fetchPairingRequest,
  markRelayMessagesSeen,
  registerRelayConversation,
  sendRelayMessage,
  createTask,
  cancelTask,
  fetchSnapshot
} from "./lib/gateway-client.js";
import { discoverDevices } from "./lib/discovery.js";
import { loadDevices, saveDevices } from "./lib/state.js";

const toolDefinitions = [
  {
    name: "watcher.discover_devices",
    description: "Discover Watcher devices on the current LAN and return diagnostics.",
    inputSchema: {
      type: "object",
      properties: {
        timeoutMs: { type: "integer", default: 2500 }
      }
    }
  },
  {
    name: "watcher.bind_device",
    description: "Bind one Watcher device using an API key or a phone-approved first-use pairing request.",
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
    description: "Read one bound device's identity and health.",
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
    description: "Read the Watcher gateway capabilities contract.",
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
    description: "Capture the current device frame as a base64 JPEG payload.",
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
    description: "Create a Watcher task such as snapshot, monitor, or video_analysis.",
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
    description: "List recent tasks on a Watcher device.",
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
    description: "Read one task snapshot by id.",
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
    description: "List task events incrementally.",
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
    description: "Poll a task until a matching event or terminal state is observed.",
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
    description: "Cancel a running Watcher task.",
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
    description: "Read current commentary and speech state.",
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
    description: "Read commentary entries, optionally incrementally.",
    inputSchema: {
      type: "object",
      properties: {
        deviceId: { type: "string" },
        baseUrl: { type: "string" },
        since: { type: "integer" }
      }
    }
  },
  {
    name: "watcher.register_relay_conversation",
    description: "Register or update a PC Agent conversation that can be continued from the phone.",
    inputSchema: {
      type: "object",
      required: ["title"],
      properties: {
        deviceId: { type: "string" },
        baseUrl: { type: "string" },
        conversationId: { type: "string" },
        title: { type: "string" },
        summary: { type: "string" },
        status: { type: "string", default: "active" }
      }
    }
  },
  {
    name: "watcher.list_relay_conversations",
    description: "List relay conversations owned by the bound PC Agent.",
    inputSchema: {
      type: "object",
      properties: {
        deviceId: { type: "string" },
        baseUrl: { type: "string" }
      }
    }
  },
  {
    name: "watcher.get_relay_messages",
    description: "Read relay chat messages from one conversation.",
    inputSchema: {
      type: "object",
      required: ["conversationId"],
      properties: {
        deviceId: { type: "string" },
        baseUrl: { type: "string" },
        conversationId: { type: "string" },
        afterMessageId: { type: "integer" }
      }
    }
  },
  {
    name: "watcher.send_relay_message",
    description: "Send a PC Agent reply into a relay conversation.",
    inputSchema: {
      type: "object",
      required: ["conversationId", "content"],
      properties: {
        deviceId: { type: "string" },
        baseUrl: { type: "string" },
        conversationId: { type: "string" },
        content: { type: "string" }
      }
    }
  },
  {
    name: "watcher.mark_relay_messages_seen",
    description: "Mark phone-authored relay messages as seen by the PC Agent.",
    inputSchema: {
      type: "object",
      required: ["conversationId"],
      properties: {
        deviceId: { type: "string" },
        baseUrl: { type: "string" },
        conversationId: { type: "string" },
        throughMessageId: { type: "integer" }
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

async function handleRegisterRelayConversation(args) {
  const { device, baseUrl, apiKey, bindingToken } = withDevice(args);
  const conversation = await registerRelayConversation({
    baseUrl,
    apiKey,
    bindingToken,
    payload: {
      conversationId: args.conversationId,
      title: args.title,
      summary: args.summary,
      status: args.status || "active"
    }
  });
  return {
    deviceId: device.deviceId,
    baseUrl,
    conversation
  };
}

async function handleListRelayConversations(args) {
  const { device, baseUrl, apiKey, bindingToken } = withDevice(args);
  const conversations = await fetchRelayConversations({ baseUrl, apiKey, bindingToken });
  return {
    deviceId: device.deviceId,
    baseUrl,
    conversations
  };
}

async function handleGetRelayMessages(args) {
  const { device, baseUrl, apiKey, bindingToken } = withDevice(args);
  const messages = await fetchRelayMessages({
    baseUrl,
    apiKey,
    bindingToken,
    conversationId: args.conversationId,
    afterMessageId: args.afterMessageId
  });
  return {
    deviceId: device.deviceId,
    baseUrl,
    conversationId: args.conversationId,
    messages
  };
}

async function handleSendRelayMessage(args) {
  const { device, baseUrl, apiKey, bindingToken } = withDevice(args);
  const message = await sendRelayMessage({
    baseUrl,
    apiKey,
    bindingToken,
    conversationId: args.conversationId,
    content: args.content
  });
  return {
    deviceId: device.deviceId,
    baseUrl,
    conversationId: args.conversationId,
    message
  };
}

async function handleMarkRelayMessagesSeen(args) {
  const { device, baseUrl, apiKey, bindingToken } = withDevice(args);
  const result = await markRelayMessagesSeen({
    baseUrl,
    apiKey,
    bindingToken,
    conversationId: args.conversationId,
    throughMessageId: args.throughMessageId
  });
  return {
    deviceId: device.deviceId,
    baseUrl,
    result
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
  ["watcher.register_relay_conversation", handleRegisterRelayConversation],
  ["watcher.list_relay_conversations", handleListRelayConversations],
  ["watcher.get_relay_messages", handleGetRelayMessages],
  ["watcher.send_relay_message", handleSendRelayMessage],
  ["watcher.mark_relay_messages_seen", handleMarkRelayMessagesSeen]
]);

const server = new Server(
  {
    name: "watcher-mcp",
    version: "0.5.0"
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
      detail.hint = "Device unreachable. Check network connectivity and that the Watcher gateway is running.";
    }
    return {
      content: [{ type: "text", text: JSON.stringify(detail, null, 2) }],
      isError: true
    };
  }
});

const transport = new StdioServerTransport();
await server.connect(transport);
