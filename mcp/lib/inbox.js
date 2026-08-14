/**
 * Local relay message inbox with persistent cursor.
 * Separates "ingestion from ntfy" from "consumption by tools".
 */
import fs from "node:fs";
import { ensureStateDir, INBOX_FILE } from "./paths.js";

export class RelayInbox {
  constructor(filePath = INBOX_FILE) {
    this.filePath = filePath;
    this.data = this._load();
  }

  // ── Ingestion ──────────────────────────────────────────────

  /**
   * Write ntfy poll results into inbox. Deduplicates by _ntfyId.
   * Returns count of newly inserted messages.
   */
  ingest(ntfyMessages, conversationId) {
    let inserted = 0;
    for (const msg of ntfyMessages) {
      if (!msg._ntfyId) continue;
      // Filter by conversation if specified
      if (conversationId && msg.conversationId !== conversationId) continue;
      // Dedup: prefer messageId (relay.v1), fallback to ntfyId + content fingerprint
      const clientMsgId = msg.messageId;
      if (clientMsgId && this.data.messages.some((m) => m.messageId === clientMsgId)) continue;
      if (this.data.messages.some((m) => m.id === msg._ntfyId)) continue;
      if (this.data.messages.some((m) =>
        m.author === msg.author && m.ts === msg.ts && m.content === msg.content
      )) continue;

      this.data.messages.push({
        id: msg._ntfyId,
        messageId: msg.messageId || null,
        ntfyTime: msg._ntfyTime || 0,
        conversationId: msg.conversationId || "",
        author: msg.author,
        type: msg.type || "message",
        content: msg.content || "",
        ts: msg.ts || (msg._ntfyTime ? msg._ntfyTime * 1000 : Date.now()),
        turnId: msg.turnId || 0,
        title: msg.title || null,
        summary: msg.summary || null,
        createdAt: msg.createdAt || null,
        receivedAt: Date.now(),
        status: "received"
      });
      inserted++;
    }

    // Cap inbox size (keep most recent 500)
    if (this.data.messages.length > 500) {
      this.data.messages = this.data.messages.slice(-500);
    }

    if (inserted > 0) this._save();
    return inserted;
  }

  // ── Consumption ────────────────────────────────────────────

  /**
   * Get unconsumed phone_user messages for a conversation (after cursor).
   */
  getUnconsumed(conversationId) {
    const cursor = this.getCursor(conversationId);
    return this.data.messages.filter((m) =>
      m.conversationId === conversationId &&
      m.author === "phone_user" &&
      m.status === "received" &&
      m.ntfyTime > (cursor?.lastConsumedNtfyTime || 0)
    );
  }

  /**
   * Get all messages for a conversation (for get_relay_messages — read-only view).
   */
  getAll(conversationId) {
    return this.data.messages
      .filter((m) => m.conversationId === conversationId)
      .map(({ id, conversationId: cid, author, type, content, ts, title, summary }) =>
        ({ conversationId: cid, author, type, content, ts, title, summary })
      );
  }

  /**
   * Mark a message as consumed and update cursor.
   */
  markConsumed(messageId) {
    const msg = this.data.messages.find((m) => m.id === messageId);
    if (!msg) return;
    msg.status = "consumed";

    // Update cursor
    const convId = msg.conversationId;
    if (!this.data.cursors[convId]) {
      this.data.cursors[convId] = {};
    }
    const cursor = this.data.cursors[convId];
    if (msg.ntfyTime > (cursor.lastConsumedNtfyTime || 0)) {
      cursor.lastConsumedId = msg.id;
      cursor.lastConsumedNtfyTime = msg.ntfyTime;
      cursor.lastConsumedTs = msg.ts;
      cursor.updatedAt = Date.now();
    }

    this._save();
  }

  // ── Cursor ─────────────────────────────────────────────────

  getCursor(conversationId) {
    return this.data.cursors[conversationId] || null;
  }

  /**
   * Returns the `since` parameter for ntfy poll, based on cursor.
   * Falls back to "5m" if no cursor exists.
   */
  getSince(conversationId) {
    const cursor = this.getCursor(conversationId);
    if (cursor?.lastConsumedNtfyTime) {
      // Poll from 10 seconds before last consumed (small overlap for safety)
      return `${cursor.lastConsumedNtfyTime - 10}`;
    }
    return "5m";
  }

  // ── Persistence ────────────────────────────────────────────

  _load() {
    ensureStateDir();
    if (!fs.existsSync(this.filePath)) {
      return { messages: [], cursors: {} };
    }
    try {
      return JSON.parse(fs.readFileSync(this.filePath, "utf8"));
    } catch {
      return { messages: [], cursors: {} };
    }
  }

  _save() {
    ensureStateDir();
    fs.writeFileSync(this.filePath, JSON.stringify(this.data, null, 2) + "\n", "utf8");
  }
}
