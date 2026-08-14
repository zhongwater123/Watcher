/**
 * ntfy publish/subscribe client for relay chat.
 * Replaces the old gateway HTTP relay endpoints.
 */

export { DEFAULT_NTFY_SERVER, DEFAULT_NTFY_TOPIC, DEFAULT_NTFY_AUTH_TOKEN, validateNtfyServerUrl };

function validateNtfyServerUrl(serverUrl) {
  const normalized = (serverUrl || "").trim();
  if (!normalized) return "ntfyServerUrl is required.";
  const lower = normalized.toLowerCase();
  if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
    return "ntfyServerUrl must use http:// or https://.";
  }
  return null;
}

function resolveNtfyTarget(serverUrl, topic) {
  const normalizedTopic = (topic || "").trim();
  const serverError = validateNtfyServerUrl(serverUrl);
  if (serverError) throw new Error(serverError);
  if (!normalizedTopic) throw new Error("ntfy topic not configured.");
  return {
    baseUrl: serverUrl.trim().replace(/\/+$/, ""),
    topic: normalizedTopic
  };
}

export async function ntfyPublish(serverUrl, topic, payload, authToken, extraHeaders = {}) {
  const { baseUrl, topic: ntfyTopic } = resolveNtfyTarget(serverUrl || DEFAULT_NTFY_SERVER, topic || DEFAULT_NTFY_TOPIC);
  // Use ntfy JSON publish API (POST to / with topic in body)
  // This ensures the payload is stored as the message text, not as an attachment
  const ntfyBody = {
    topic: ntfyTopic,
    message: JSON.stringify(payload),
    title: `relay:${payload.conversationId || "default"}`
  };
  // Map extra headers to ntfy JSON fields
  if (extraHeaders["X-Priority"]) {
    const priorityMap = { min: 1, low: 2, default: 3, high: 4, urgent: 5 };
    ntfyBody.priority = priorityMap[extraHeaders["X-Priority"]] || parseInt(extraHeaders["X-Priority"]) || 3;
  }
  if (extraHeaders["X-Tags"]) {
    ntfyBody.tags = extraHeaders["X-Tags"].split(",").map((t) => t.trim());
  }
  const headers = { "Content-Type": "application/json" };
  if (authToken) {
    headers.Authorization = `Bearer ${authToken}`;
  }
  let response;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      response = await fetch(baseUrl, {
        method: "POST",
        headers,
        body: JSON.stringify(ntfyBody),
        signal: AbortSignal.timeout(20000)
      });
      break;
    } catch (err) {
      if (attempt === 1) throw err;
      // First attempt failed, retry once
    }
  }
  if (!response.ok) {
    const error = new Error(`ntfy publish failed: ${response.status} ${response.statusText}`);
    error.status = response.status;
    throw error;
  }
}

const DEFAULT_NTFY_SERVER = "";
const DEFAULT_NTFY_TOPIC = "";  // Resolved from stored relay config or user input
const DEFAULT_NTFY_AUTH_TOKEN = null;

export async function ntfyPoll(serverUrl, topic, { since = "1h", authToken, conversationId } = {}) {
  const { baseUrl, topic: ntfyTopic } = resolveNtfyTarget(serverUrl || DEFAULT_NTFY_SERVER, topic || DEFAULT_NTFY_TOPIC);
  const url = `${baseUrl}/${encodeURIComponent(ntfyTopic)}/json?poll=1&since=${since}`;
  const headers = {};
  if (authToken) {
    headers.Authorization = `Bearer ${authToken}`;
  }
  const response = await fetch(url, {
    headers,
    signal: AbortSignal.timeout(8000)
  });
  if (!response.ok) {
    const error = new Error(`ntfy poll failed: ${response.status} ${response.statusText}`);
    error.status = response.status;
    throw error;
  }
  const text = await response.text();
  const messages = text
    .trim()
    .split("\n")
    .filter((line) => line.length > 0)
    .map((line) => {
      try {
        return JSON.parse(line);
      } catch {
        return null;
      }
    })
    .filter((msg) => msg && msg.event === "message" && msg.message)
    .map((msg) => {
      try {
        const payload = JSON.parse(msg.message);
        if (!payload.author) return null;
        if (!payload.conversationId && payload.type !== "presence") return null;
        // Preserve ntfy envelope metadata for dedup and cursor
        payload._ntfyId = payload.messageId || msg.id;
        payload._ntfyTime = msg.time;
        return payload;
      } catch {
        return null;
      }
    })
    .filter(Boolean);

  if (conversationId) {
    return messages.filter((m) => m.conversationId === conversationId);
  }
  return messages;
}
