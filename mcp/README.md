# watcher-mcp

MCP server that gives AI agents real-world perception through the [Watcher](https://github.com/AAswordman/Watcher) Android app gateway.

## What it does

- Discovers Watcher devices on the local network
- Binds a device through phone-approved first-use pairing and caches connection details
- Reads gateway capabilities and health
- Captures live camera snapshots
- Creates and manages monitoring/analysis tasks
- Polls task events and commentary state
- Relays phone-to-PC Agent chat handoff messages

## What it does not do

- Desktop automation (Git, file ops, lock screen)
- Replace the agent's own terminal or OS tools
- Hardcode scenario-specific workflows

Watcher is the agent's **eyes**, not its hands.

## Install

```bash
npm install -g watcher-mcp
```

Or use without installing:

```bash
npx -y watcher-mcp
```

## Configure with Claude Code

One command:

```bash
claude mcp add --transport stdio watcher -- npx -y watcher-mcp
```

Or add to your project's `.mcp.json`:

```json
{
  "mcpServers": {
    "watcher": {
      "command": "npx",
      "args": ["-y", "watcher-mcp"]
    }
  }
}
```

## Configure with Codex

```bash
codex mcp add watcher -- npx -y watcher-mcp
```

Or in `~/.codex/config.toml`:

```toml
[mcp_servers.watcher]
command = "npx"
args = ["-y", "watcher-mcp"]
```

## Requirements

- Node.js 18+
- Watcher app running on an Android device with gateway enabled
- Phone and computer on the same LAN

## Tools

| Tool | Description |
|------|-------------|
| `watcher.discover_devices` | Find Watcher devices on LAN via mDNS + subnet scan |
| `watcher.bind_device` | Pair with a device using phone approval, or using its URL and API key as a fallback |
| `watcher.get_device` | Read device identity and health |
| `watcher.get_capabilities` | Read the gateway protocol contract |
| `watcher.capture_snapshot` | Get current camera frame as JPEG |
| `watcher.create_task` | Create a monitor or video_analysis task |
| `watcher.list_tasks` | List recent tasks |
| `watcher.get_task` | Get one task with status and events |
| `watcher.list_task_events` | Poll task events incrementally |
| `watcher.wait_for_condition` | Block until a matching event fires |
| `watcher.cancel_task` | Cancel a running task |
| `watcher.get_commentary_state` | Read live commentary state |
| `watcher.list_commentary_entries` | Read commentary entries |
| `watcher.register_relay_conversation` | Register or update a PC Agent conversation visible on the phone |
| `watcher.list_relay_conversations` | List relay conversations owned by the bound Agent |
| `watcher.get_relay_messages` | Read phone and Agent messages for one relay conversation |
| `watcher.send_relay_message` | Send a PC Agent reply back to the phone |
| `watcher.mark_relay_messages_seen` | Mark phone-authored messages as seen by the PC Agent |

## Pairing model

`watcher.bind_device` supports two compatible paths:

- With `apiKey`: uses the legacy `POST /api/device/pair` gateway route.
- Without `apiKey`: discovers or uses `baseUrl`, creates `POST /api/device/pair-requests`, then polls until the phone approves, rejects, expires, or times out.

After phone approval, Watcher returns a `bindingToken`. The MCP server caches it locally and uses it for later gateway calls.

## Relay chat flow

Watcher Relay Chat is a phone-visible handoff channel. It does not inject text into the native Claude Code or Codex input box. Instead, the PC Agent calls MCP tools to register its current work, read phone messages, and write replies back to the phone.

```text
1. Agent -> watcher.register_relay_conversation (title + summary of current PC work)
2. User opens Watcher -> Multi-device -> Chat, selects the bound Agent and conversation
3. User sends a phone message into the relay conversation
4. Agent -> watcher.get_relay_messages
5. Agent sees the phone message in its MCP tool result
6. Agent -> watcher.send_relay_message
7. Phone shows the Agent reply in the same conversation
```

## Typical automation flow

```text
1. User: "Back up the project when I leave my desk"
2. Agent -> watcher.bind_device (if not cached; approve the first request on the phone)
3. Agent -> watcher.create_task (monitor: "detect user leaving desk")
4. Agent -> watcher.wait_for_condition (eventDataContains: "ALERT")
5. Watcher detects user left
6. Agent -> git add && git commit && git push (using its own tools)
7. Agent -> watcher.cancel_task
```

## Local state

Device bindings are cached at `~/.watcher-mcp/devices.json`. This file contains API keys or binding tokens; do not share it.

For local test isolation, set `WATCHER_MCP_STATE_DIR` to override the state directory.

## Development

```bash
git clone https://github.com/AAswordman/Watcher.git
cd Watcher/mcp
npm install
npm run check
npm test
```

For local development, use a direct path in `.mcp.json`:

```json
{
  "mcpServers": {
    "watcher": {
      "command": "node",
      "args": ["./mcp/server.js"]
    }
  }
}
```

## Known limitations

- Enterprise networks may block phone-to-computer communication
- mDNS discovery depends on network multicast support
- The gateway is designed for trusted LAN use, not public internet

## License

MIT
