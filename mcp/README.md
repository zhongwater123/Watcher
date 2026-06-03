# watcher-mcp

MCP server that gives AI agents real-world perception through the [Watcher](https://github.com/AAswordman/Watcher) Android app gateway.

## What it does

- Discovers Watcher devices on the local network
- Binds a device and caches connection details
- Reads gateway capabilities and health
- Captures live camera snapshots
- Creates and manages monitoring/analysis tasks
- Polls task events and commentary state

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
| `watcher_discover_devices` | Find Watcher devices on LAN via mDNS + subnet scan |
| `watcher_bind_device` | Pair with a device using its URL and API key |
| `watcher_get_device` | Read device identity and health |
| `watcher_get_capabilities` | Read the gateway protocol contract |
| `watcher_capture_snapshot` | Get current camera frame as JPEG |
| `watcher_create_task` | Create a monitor or video_analysis task |
| `watcher_list_tasks` | List recent tasks |
| `watcher_get_task` | Get one task with status and events |
| `watcher_list_task_events` | Poll task events incrementally |
| `watcher_wait_for_condition` | Block until a matching event fires |
| `watcher_cancel_task` | Cancel a running task |
| `watcher_get_commentary_state` | Read live commentary state |
| `watcher_list_commentary_entries` | Read commentary entries |

## Typical flow

```
1. User: "备份项目当我离开工位"
2. Agent → watcher_bind_device (if not cached)
3. Agent → watcher_create_task (monitor: "detect user leaving desk")
4. Agent → watcher_wait_for_condition (eventDataContains: "ALERT")
5. Watcher detects user left
6. Agent → git add && git commit && git push (using its own tools)
7. Agent → watcher_cancel_task
```

## Local state

Device bindings are cached at `~/.watcher-mcp/devices.json`. This file contains your API keys — do not share it.

## Development

```bash
git clone https://github.com/AAswordman/Watcher.git
cd Watcher/mcp
npm install
npm run check   # syntax validation
npm test        # integration tests (31 assertions)
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
