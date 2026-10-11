// Messages (SPEC §3, §6, §7, §9): post, fetch, cumulative ack, WebSocket.

import { callerOf, ok, stillActiveCaller, type Handler, type HubRuntime } from "../call";
import { b64decode } from "../encoding";
import { HttpError, badRequest } from "../errors";
import { parseEnvelope, verifyEnvelope } from "../envelope";
import { backlogOf, hasLiveSocket, messageFrame, safeSend, sendMessageFrame, setBacklog } from "../sockets";
import { deleteBlobsOfMessage } from "../store/blobs";
import { activeDeviceIds, setCursor, type DeviceRow } from "../store/devices";
import {
  deleteMessage,
  findByCollapse,
  findByMsgId,
  highestSeq,
  insertMessage,
  pendingCount,
  tombstoneDelivered,
  unackedFor,
} from "../store/messages";
import { expectInt, expectKeys, expectObject, parseIntParam, readJson } from "../validate";

export const MAX_FETCH_LIMIT = 200;
export const DEFAULT_FETCH_LIMIT = 100;
/** Per-recipient queue limit (SPEC §6 rule 6). */
export const MAX_PENDING = 1000;
/** One WebSocket backlog page (SPEC §7). */
export const WS_PAGE_MESSAGES = 100;
export const WS_PAGE_BYTES = 1024 * 1024;

/**
 * Recipients with a live WebSocket right now (SPEC §6). Sockets are tagged with their device id,
 * so this reads no storage and works while the Hub hibernates. A phone uses it to open its
 * action window.
 */
function onlineOf(hub: HubRuntime, to: readonly string[]): string[] {
  return to.filter((id) => hasLiveSocket(hub.ctx, id));
}

/** How far a msg_id timestamp may be ahead of the Hub's clock, or behind the end of its ttl (SPEC §6 rule 8). */
export const MSG_ID_SKEW_MS = 300_000;

/** The 48-bit Unix-ms timestamp of a UUIDv7 (already validated by parseEnvelope). */
function msgIdTime(msgId: string): number {
  return parseInt(msgId.slice(0, 8) + msgId.slice(9, 13), 16);
}

export const post: Handler = async (call) => {
  const { hub, now } = call;
  const caller = callerOf(call);
  const body = expectObject(await readJson(call.request), "body");
  expectKeys(body, "body", ["envelope"]);
  const parsed = parseEnvelope(body.envelope);
  const env = parsed.envelope;

  // A device with a wrong clock would mint ids that expire at once or sit in the future.
  const t = msgIdTime(env.msg_id);
  if (t > now + MSG_ID_SKEW_MS || t + env.ttl_s * 1000 + MSG_ID_SKEW_MS < now) {
    throw new HttpError("bad_request", "msg_id time is outside the allowed window; check the device clock", { clock_skew: true, hub_time: now });
  }

  if (env.from !== caller.id) throw new HttpError("forbidden", "envelope.from must be the calling device");
  if (!(await verifyEnvelope(parsed, b64decode(caller.sign_pub)!))) throw badRequest("envelope.sig does not verify");

  // Everything below is synchronous, so it runs atomically.
  stillActiveCaller(call);
  const active = activeDeviceIds(hub.sql);
  const inactive = env.to.filter((id) => !active.has(id));
  if (inactive.length > 0) throw new HttpError("conflict", "some recipients are not active devices", { inactive });

  const existing = findByMsgId(hub.sql, caller.id, env.msg_id);
  if (existing !== null) return ok({ seq: existing, duplicate: true, online: onlineOf(hub, env.to) });

  const stored = env.collapse === undefined ? null : findByCollapse(hub.sql, caller.id, env.collapse);
  // UUIDv7 strings in lowercase hex sort in creation order.
  if (stored && env.msg_id < stored.msg_id) {
    return ok({ seq: null, duplicate: false, superseded: true, online: onlineOf(hub, env.to) });
  }

  const full = env.to.filter((id) => pendingCount(hub.sql, id, now, MAX_PENDING, stored?.seq ?? 0) >= MAX_PENDING);
  if (full.length > 0) throw new HttpError("rate_limited", `recipients may have at most ${MAX_PENDING} pending messages`, { full });

  if (stored) {
    deleteMessage(hub.sql, stored.seq);
    deleteBlobsOfMessage(hub.sql, caller.id, stored.msg_id);
  }
  const seq = insertMessage(hub.sql, {
    sender: caller.id,
    msgId: env.msg_id,
    collapse: env.collapse ?? null,
    recipients: env.to,
    envelope: parsed.serialized,
    expiresAt: now + env.ttl_s * 1000,
  });

  const online = onlineOf(hub, env.to);
  for (const id of online) sendMessageFrame(hub.ctx, id, seq, parsed.serialized);
  await hub.scheduleAlarm();
  return ok({ seq, duplicate: false, online });
};

export const fetchMessages: Handler = (call) => {
  const { hub, now } = call;
  const caller = callerOf(call);
  const limit = parseIntParam(call.url.searchParams.get("limit"), "limit", DEFAULT_FETCH_LIMIT, 1, MAX_FETCH_LIMIT);
  const rows = unackedFor(hub.sql, caller.id, caller.cursor, now, limit + 1).toArray();
  // Envelopes are stored as JSON text; splice them in instead of re-parsing.
  const items = rows.slice(0, limit).map((r) => `{"seq":${r.seq},"envelope":${r.envelope}}`);
  return new Response(`{"messages":[${items.join(",")}],"more":${rows.length > limit}}`, {
    headers: { "content-type": "application/json" },
  });
};

/**
 * Sends the next backlog page after `after`: at most WS_PAGE_MESSAGES messages or WS_PAGE_BYTES,
 * reading no more rows than it sends (plus one to know whether the backlog is drained).
 */
function sendPage(sql: SqlStorage, ws: WebSocket, deviceId: string, after: number, now: number): void {
  let sent = after;
  let count = 0;
  let bytes = 0;
  let done = true;
  for (const row of unackedFor(sql, deviceId, after, now, WS_PAGE_MESSAGES + 1)) {
    const frame = messageFrame(row.seq, row.envelope);
    // Envelopes are ASCII JSON, so length is the byte size.
    if (count === WS_PAGE_MESSAGES || (count > 0 && bytes + frame.length > WS_PAGE_BYTES)) {
      done = false;
      break;
    }
    safeSend(ws, frame);
    sent = row.seq;
    count++;
    bytes += frame.length;
  }
  setBacklog(ws, { sent, done });
}

/**
 * Advances the device's cumulative cursor, reduces fully delivered messages to tombstones, and
 * sends the next backlog page to each of its sockets whose last page is now fully acknowledged.
 */
export function applyAck(hub: Pick<HubRuntime, "sql" | "ctx">, device: DeviceRow, upTo: number, now: number): void {
  const { sql, ctx } = hub;
  // Never move a cursor past the highest seq issued, so later seqs are never skipped.
  const target = Math.min(upTo, highestSeq(sql));
  if (target > device.cursor) {
    setCursor(sql, device.id, target);
    tombstoneDelivered(sql, device.cursor, target);
  }
  const cursor = Math.max(target, device.cursor);
  for (const ws of ctx.getWebSockets(device.id)) {
    const backlog = backlogOf(ws);
    if (!backlog.done && cursor >= backlog.sent) sendPage(sql, ws, device.id, cursor, now);
  }
}

export const acks: Handler = async (call) => {
  const body = expectObject(await readJson(call.request), "body");
  expectKeys(body, "body", ["up_to"]);
  const upTo = expectInt(body.up_to, "up_to", 0, Number.MAX_SAFE_INTEGER);
  // Re-read the caller: it may have been revoked, or its cursor moved, while the body was read.
  applyAck(call.hub, stillActiveCaller(call), upTo, call.now);
  return ok();
};

export const websocket: Handler = (call) => {
  const { hub, now } = call;
  const caller = callerOf(call);
  if (call.request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
    throw new HttpError("bad_request", "expected a WebSocket upgrade");
  }
  const [client, server] = Object.values(new WebSocketPair()) as [WebSocket, WebSocket];
  hub.ctx.acceptWebSocket(server, [caller.id]);
  sendPage(hub.sql, server, caller.id, caller.cursor, now);
  return new Response(null, { status: 101, webSocket: client });
};
