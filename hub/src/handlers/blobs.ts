// Encrypted blobs (SPEC §6, §11): PUT, HEAD and GET /v1/blobs/:id. The Hub stores ciphertext
// addressed by b64(sha256(ct)). It checks the hash, size, quota and expiry, never the content.

import { callerOf, stillActiveCaller, type Call, type Handler } from "../call";
import { sha256 } from "../crypto";
import { b64encode } from "../encoding";
import { HttpError, badRequest } from "../errors";
import { UUID_V7 } from "../ids";
import { BLOB_BODY_LIMIT } from "../routes";
import { blobBytes, extendBlob, getBlob, getBlobData, insertBlob } from "../store/blobs";

/** Total ciphertext kept across all blobs: 256 MiB (SPEC §6). */
export const BLOB_QUOTA_BYTES = 256 * 1024 * 1024;
export const MIN_BLOB_TTL_S = 60;
export const MAX_BLOB_TTL_S = 604_800;
export const EXPIRES_HEADER = "x-expires-at";

/** b64 of exactly 32 bytes: the SHA-256 of the ciphertext. */
const BLOB_ID = /^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/;

function blobIdOf(call: Call): string {
  const id = call.params.id!;
  if (!BLOB_ID.test(id)) throw badRequest("blob id must be b64(sha256(ct))");
  return id;
}

/** `ttl_s` clamped to 60..604800, or null when absent. */
function ttlOf(url: URL): number | null {
  const raw = url.searchParams.get("ttl_s");
  if (raw === null) return null;
  if (!/^\d{1,15}$/.test(raw)) throw badRequest("ttl_s must be an integer");
  return Math.min(MAX_BLOB_TTL_S, Math.max(MIN_BLOB_TTL_S, Number(raw)));
}

function msgIdOf(url: URL): string | null {
  const raw = url.searchParams.get("msg_id");
  if (raw !== null && !UUID_V7.test(raw)) throw badRequest("msg_id must be a lowercase UUIDv7");
  return raw;
}

export const put: Handler = async (call) => {
  const { hub, now } = call;
  callerOf(call);
  const id = blobIdOf(call);
  const ttlS = ttlOf(call.url);
  if (ttlS === null) throw badRequest("ttl_s is required");
  const msgId = msgIdOf(call.url);
  const data = await call.request.arrayBuffer();
  if (data.byteLength === 0) throw badRequest("body must not be empty");
  // The Worker enforces this first; the Hub never stores more than the route allows.
  if (data.byteLength > BLOB_BODY_LIMIT) throw new HttpError("too_large", `body exceeds ${BLOB_BODY_LIMIT} bytes`);
  if (b64encode(await sha256(new Uint8Array(data))) !== id) throw badRequest("blob id does not match sha256(body)");

  // Everything below is synchronous, so it runs atomically.
  const caller = stillActiveCaller(call);
  const expiresAt = now + ttlS * 1000;
  const existing = getBlob(hub.sql, id);
  if (existing) {
    const later = Math.max(existing.expires_at, expiresAt);
    // Uploaded again for another message, or for none: it must outlive the first one.
    extendBlob(hub.sql, id, later, existing.msg_id !== null && existing.msg_id !== msgId);
    return Response.json({ id, size: existing.size, expires_at: later });
  }
  if (blobBytes(hub.sql) + data.byteLength > BLOB_QUOTA_BYTES) {
    throw new HttpError("rate_limited", "blob storage is full", { quota: true });
  }
  insertBlob(hub.sql, { id, owner: caller.id, msgId, data, expiresAt });
  await hub.scheduleAlarm();
  return Response.json({ id, size: data.byteLength, expires_at: expiresAt }, { status: 201 });
};

export const head: Handler = (call) => {
  const { hub, now } = call;
  const id = blobIdOf(call);
  const touch = call.url.searchParams.get("touch");
  if (touch !== null && touch !== "1") throw badRequest("touch must be 1");
  const ttlS = ttlOf(call.url);
  if (touch === "1" && ttlS === null) throw badRequest("ttl_s is required with touch=1");
  const blob = getBlob(hub.sql, id);
  if (!blob || blob.expires_at <= now) throw new HttpError("not_found", "no such blob");
  let expiresAt = blob.expires_at;
  if (touch === "1" && ttlS !== null && now + ttlS * 1000 > expiresAt) {
    expiresAt = now + ttlS * 1000;
    extendBlob(hub.sql, id, expiresAt, false);
  }
  return new Response(null, { headers: { [EXPIRES_HEADER]: String(expiresAt) } });
};

export const get: Handler = (call) => {
  const blob = getBlobData(call.hub.sql, blobIdOf(call), call.now);
  if (!blob) throw new HttpError("not_found", "no such blob");
  return new Response(blob.data, {
    headers: { "content-type": "application/octet-stream", [EXPIRES_HEADER]: String(blob.expires_at) },
  });
};
