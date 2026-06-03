function buildHeaders({ apiKey, bindingToken, accept } = {}) {
  const headers = {};
  if (apiKey) {
    headers["X-API-Key"] = apiKey;
  }
  if (bindingToken) {
    headers.Authorization = `Bearer ${bindingToken}`;
  }
  if (accept) {
    headers.Accept = accept;
  }
  return headers;
}

async function parseResponse(response) {
  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("application/json")) {
    const payload = await response.json();
    return payload.data ?? payload;
  }
  const arrayBuffer = await response.arrayBuffer();
  return {
    rawBytesBase64: Buffer.from(arrayBuffer).toString("base64"),
    mimeType: contentType || "application/octet-stream"
  };
}

async function request(baseUrl, path, options = {}) {
  const timeoutMs = options.timeoutMs || 10000;
  delete options.timeoutMs;
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    signal: AbortSignal.timeout(timeoutMs)
  });
  const payload = await parseResponse(response);
  if (!response.ok) {
    const error = new Error(payload.error || `HTTP ${response.status}`);
    error.status = response.status;
    error.payload = payload;
    throw error;
  }
  return payload;
}

function withQuery(path, query = {}) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== "") {
      params.set(key, String(value));
    }
  }
  const qs = params.toString();
  return qs ? `${path}?${qs}` : path;
}

export async function fetchHealth(baseUrl) {
  return request(baseUrl, "/api/health");
}

export async function fetchIdentity(baseUrl) {
  return request(baseUrl, "/api/device/identity");
}

export async function fetchCapabilities({ baseUrl, apiKey }) {
  return request(baseUrl, "/api/capabilities", {
    headers: buildHeaders({ apiKey })
  });
}

export async function pairDevice({ baseUrl, apiKey, bridgeId, bridgeName }) {
  return request(baseUrl, "/api/device/pair", {
    method: "POST",
    headers: {
      ...buildHeaders({ apiKey }),
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ bridgeId, bridgeName })
  });
}

export async function fetchSnapshot({ baseUrl, apiKey }) {
  const payload = await request(baseUrl, "/api/stream/snapshot", {
    headers: buildHeaders({ apiKey, accept: "image/jpeg" })
  });
  return {
    mimeType: payload.mimeType,
    dataUrl: `data:${payload.mimeType};base64,${payload.rawBytesBase64}`,
    sizeBytes: Buffer.from(payload.rawBytesBase64, "base64").length
  };
}

export async function createTask({ baseUrl, apiKey, payload }) {
  return request(baseUrl, "/api/tasks", {
    method: "POST",
    headers: {
      ...buildHeaders({ apiKey }),
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  });
}

export async function fetchTasks({ baseUrl, apiKey }) {
  return request(baseUrl, "/api/tasks", {
    headers: buildHeaders({ apiKey })
  });
}

export async function fetchTask({ baseUrl, apiKey, taskId }) {
  return request(baseUrl, `/api/tasks/${encodeURIComponent(taskId)}`, {
    headers: buildHeaders({ apiKey })
  });
}

export async function fetchTaskEvents({ baseUrl, apiKey, taskId, afterEventId, since }) {
  return request(
    baseUrl,
    withQuery(`/api/tasks/${encodeURIComponent(taskId)}/events`, {
      afterEventId,
      since
    }),
    {
      headers: buildHeaders({ apiKey })
    }
  );
}

export async function cancelTask({ baseUrl, apiKey, taskId }) {
  return request(baseUrl, `/api/tasks/${encodeURIComponent(taskId)}`, {
    method: "DELETE",
    headers: buildHeaders({ apiKey })
  });
}

export async function fetchCommentaryState({ baseUrl, apiKey }) {
  return request(baseUrl, "/api/commentary/state", {
    headers: buildHeaders({ apiKey })
  });
}

export async function fetchCommentaryEntries({ baseUrl, apiKey, since }) {
  return request(baseUrl, withQuery("/api/commentary/entries", { since }), {
    headers: buildHeaders({ apiKey })
  });
}
