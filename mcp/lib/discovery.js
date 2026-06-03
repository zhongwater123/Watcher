import os from "node:os";
import { Bonjour } from "bonjour-service";
import { loadDevices } from "./state.js";

const PROBE_TIMEOUT_MS = 1500;

function isPrivateIpv4(address) {
  return address.startsWith("10.") || address.startsWith("192.168.") || /^172\.(1[6-9]|2\d|3[0-1])\./.test(address);
}

function enumerateCandidateHosts() {
  const candidates = new Set();
  const interfaces = os.networkInterfaces();
  for (const iface of Object.values(interfaces)) {
    for (const entry of iface || []) {
      if (entry.family !== "IPv4" || entry.internal || !isPrivateIpv4(entry.address)) {
        continue;
      }
      const octets = entry.address.split(".");
      const prefix = `${octets[0]}.${octets[1]}.${octets[2]}`;
      for (let host = 1; host <= 254; host += 1) {
        if (host === Number(octets[3])) {
          continue;
        }
        candidates.add(`${prefix}.${host}`);
      }
    }
  }
  return Array.from(candidates);
}

async function probeHealth(baseUrl) {
  try {
    const response = await fetch(`${baseUrl}/api/health`, {
      signal: AbortSignal.timeout(PROBE_TIMEOUT_MS)
    });
    if (!response.ok) return null;
    const payload = await response.json();
    const health = payload.data ?? payload;
    if (health.status !== "ok") return null;
    return { baseUrl, health };
  } catch {
    return null;
  }
}

async function probeIdentity(baseUrl) {
  try {
    const response = await fetch(`${baseUrl}/api/device/identity`, {
      signal: AbortSignal.timeout(PROBE_TIMEOUT_MS)
    });
    if (!response.ok) return null;
    const payload = await response.json();
    return payload.data ?? payload;
  } catch {
    return null;
  }
}

async function probeFull(baseUrl) {
  const health = await probeHealth(baseUrl);
  if (!health) return null;
  const identity = await probeIdentity(baseUrl);
  return { baseUrl, health: health.health, identity, source: "http_probe" };
}

export async function discoverDevices({ timeoutMs = 5000, ports = [8080, 8081, 8090] } = {}) {
  const results = [];
  const seen = new Set();
  const diagnostics = {
    cachedProbe: { attempted: false, hit: false },
    mdns: { attempted: true, servicesSeen: 0 },
    subnetScan: { attempted: false, candidateCount: 0, scannedCount: 0 }
  };

  // Phase 0: Try cached/known devices first (instant)
  const cached = loadDevices();
  if (cached.length > 0) {
    diagnostics.cachedProbe.attempted = true;
    const cachedProbes = await Promise.all(
      cached.map((d) => probeFull(d.baseUrl))
    );
    for (const result of cachedProbes) {
      if (result) {
        diagnostics.cachedProbe.hit = true;
        seen.add(result.baseUrl);
        results.push({ ...result, source: "cached" });
      }
    }
    if (results.length > 0) {
      return { devices: results, diagnostics };
    }
  }

  // Phase 1: mDNS + subnet scan in parallel, hard timeout wins
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), timeoutMs);

  const [mdnsResult, scanResult] = await Promise.all([
    mdnsDiscover(timeoutMs, seen, diagnostics, abort.signal),
    subnetScan(ports, seen, diagnostics, abort.signal)
  ]);

  clearTimeout(timer);

  if (mdnsResult) results.push(mdnsResult);
  if (scanResult) results.push(scanResult);

  return { devices: results, diagnostics };
}

async function mdnsDiscover(timeoutMs, seen, diagnostics, abortSignal) {
  return new Promise((resolve) => {
    let resolved = false;
    const bonjour = new Bonjour();
    const browser = bonjour.find({ type: "watcher" });

    const cleanup = () => {
      browser.stop();
      bonjour.destroy();
    };

    const done = (value) => {
      if (resolved) return;
      resolved = true;
      clearTimeout(timer);
      cleanup();
      resolve(value);
    };

    const timer = setTimeout(() => done(null), timeoutMs);
    abortSignal?.addEventListener("abort", () => done(null), { once: true });

    browser.on("up", async (service) => {
      diagnostics.mdns.servicesSeen += 1;
      const host = service.referer?.address || service.addresses?.find((a) => a.includes("."));
      const port = service.port;
      if (!host || !port) return;

      const baseUrl = `http://${host}:${port}`;
      if (seen.has(baseUrl)) return;
      seen.add(baseUrl);

      const result = await probeFull(baseUrl);
      if (result) {
        done({
          ...result,
          source: "mdns",
          mdns: { name: service.name, txt: service.txt || {} }
        });
      }
    });
  });
}

async function subnetScan(ports, seen, diagnostics, abortSignal) {
  diagnostics.subnetScan.attempted = true;
  const candidates = [];
  for (const host of enumerateCandidateHosts()) {
    for (const port of ports) {
      const baseUrl = `http://${host}:${port}`;
      if (!seen.has(baseUrl)) {
        seen.add(baseUrl);
        candidates.push(baseUrl);
      }
    }
  }
  diagnostics.subnetScan.candidateCount = candidates.length;

  const concurrency = 128;
  for (let i = 0; i < candidates.length; i += concurrency) {
    if (abortSignal?.aborted) break;
    const batch = candidates.slice(i, i + concurrency);
    diagnostics.subnetScan.scannedCount += batch.length;
    const probes = await Promise.all(batch.map(probeHealth));
    const hit = probes.find((p) => p !== null);
    if (hit) {
      const identity = await probeIdentity(hit.baseUrl);
      return { baseUrl: hit.baseUrl, health: hit.health, identity, source: "subnet_scan" };
    }
  }
  return null;
}
