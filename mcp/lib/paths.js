import fs from "node:fs";
import os from "node:os";
import path from "node:path";

export const STATE_DIR = process.env.WATCHER_MCP_STATE_DIR || path.join(os.homedir(), ".watcher-mcp");
export const DEVICES_FILE = path.join(STATE_DIR, "devices.json");
export const INBOX_FILE = path.join(STATE_DIR, "inbox.json");
export const RELAY_CONFIG_FILE = path.join(STATE_DIR, "relay-config.json");

export function ensureStateDir() {
  fs.mkdirSync(STATE_DIR, { recursive: true });
}
