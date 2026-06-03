import fs from "node:fs";
import {
  ensureStateDir,
  DEVICES_FILE
} from "./paths.js";

function readJson(filePath, fallback) {
  ensureStateDir();
  if (!fs.existsSync(filePath)) {
    return fallback;
  }
  try {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
  } catch {
    return fallback;
  }
}

function writeJson(filePath, value) {
  ensureStateDir();
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

export function loadDevices() {
  return readJson(DEVICES_FILE, []);
}

export function saveDevices(devices) {
  writeJson(DEVICES_FILE, devices);
}
