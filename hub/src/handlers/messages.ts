// Messages (SPEC §3, §6, §7, §9): post, fetch, cumulative ack, WebSocket.

import { callerOf, ok, type Handler } from "../call";
import { b64decode } from "../encoding";
import { HttpError, badRequest } from "../errors";
import { parseEnvelope, verifyEnvelope } from "../envelope";
import { hasLiveSocket, messageFrame, sendMessageFrame } from "../sockets";
import { activeDeviceIds, getDevice, rearmPush, setCursor, type DeviceRow } from "../store/devices";
import {
  deleteMessage,
  findByCollapse,
  findByMsgId,
  highestSeq,
  insertMessage,
  purgeDelivered,
  unackedFor,
} from "../store/messages";
import { expectInt, expectKeys, expectObject, parseIntParam, readJson } from "../validate";

export const MAX_FETCH_LIMIT = 200;
export const DEFAULT_FETCH_LIMIT = 100;

export const post: Handler = async (call) => {
  const { hub, now } = call;
  const caller = callerOf(call);
  const body = expectObject(await readJson(call.request), "body");
  expectKeys(body, "body", ["envelope"]);
  const parsed = parseEnvelope(body.envelope);
  const env = parsed.envelope;

  if (env.from !== caller.id) throw new HttpError("forbidden", "envelope.from must be the calling device");
  if (!(await verifyEnvelope(parsed, b64decode(caller.sign_pub)!))) throw badRequest("envelope.sig does not verify");

  // Everything below is synchronous, so it runs atomically.
  const active = activeDeviceIds(hub.sql);
  if (!active.has(caller.id)) throw new HttpError("unauthorized", "device was revoked");
  const inactive = env.to.filter((id) => !active.has(id));
  if (inactive.length > 0) throw new HttpError("conflict", "some recipients are not active devices", { inactive });

  const existing = findByMsgId(hub.sql, caller.id, env.msg_id);
  if (existing !== null) return ok({ seq: existing, duplicate: true });

  if (env.collapse !== undefined) {
    const stored = findByCollapse(hub.sql, caller.id, env.collapse);
    if (stored) {
      // UUIDv7 strings in lowercase hex sort in creation order.
      if (env.msg_id < stored.msg_id) return ok({ seq: null, duplicate: false, superseded: true });
      deleteMessage(hub.sql, stored.seq);
    }
  }

  const seq = insertMessage(hub.sql, {
    sender: caller.id,
    msgId: env.msg_id,
    collapse: env.collapse ?? null,
    recipients: env.to,
    envelope: parsed.serialized,
    expiresAt: now + env.ttl_s * 1000,
  });

  for (const id of env.to) {
    if (hasLiveSocket(hub.ctx, id)) {
      sendMessageFrame(hub.ctx, id, seq, parsed.serialized);
    } else {
      const recipient = getDevice(hub.sql, id);
      if (recipient) hub.push.wake(recipient, call.url.origin, now);
    }
  }
  await hub.scheduleAlarm();
  return ok({ seq, duplicate: false });
};

export const fetchMessages: Handler = (call) => {
  const { hub, now } = call;
  const caller = callerOf(call);
  const limit = parseIntParam(call.url.searchParams.get("limit"), "limit", DEFAULT_FETCH_LIMIT, 1, MAX_FETCH_LIMIT);
  const rows = unackedFor(hub.sql, caller.id, caller.cursor, now, limit + 1);
  rearmPush(hub.sql, caller);
  // Envelopes are stored as JSON text; splice them in instead of re-parsing.
  const items = rows.slice(0, limit).map((r) => `{"seq":${r.seq},"envelope":${r.envelope}}`);
  return new Response(`{"messages":[${items.join(",")}],"more":${rows.length > limit}}`, {
    headers: { "content-type": "application/json" },
  });
};

/** Advances the device's cumulative cursor and deletes fully delivered messages. */
export function applyAck(sql: SqlStorage, device: DeviceRow, upTo: number): void {
  // Never move a cursor past the highest seq issued, so later seqs are never skipped.
  const target = Math.min(upTo, highestSeq(sql));
  if (target <= device.cursor) return;
  setCursor(sql, device.id, target);
  purgeDelivered(sql);
}

export const acks: Handler = async (call) => {
  const body = expectObject(await readJson(call.request), "body");
  expectKeys(body, "body", ["up_to"]);
  const upTo = expectInt(body.up_to, "up_to", 0, Number.MAX_SAFE_INTEGER);
  // Re-read the cursor: it may have moved while the body was being read.
  const device = getDevice(call.hub.sql, callerOf(call).id);
  if (device) applyAck(call.hub.sql, device, upTo);
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
  for (const row of unackedFor(hub.sql, caller.id, caller.cursor, now, -1)) {
    server.send(messageFrame(row.seq, row.envelope));
  }
  rearmPush(hub.sql, caller);
  return new Response(null, { status: 101, webSocket: client });
};
