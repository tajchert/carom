// Test clients. Crypto here is written independently of src/ with @noble/*, following the spec,
// so signature checks in the Hub are exercised against a second implementation.

import { chacha20poly1305 } from "@noble/ciphers/chacha.js";
import { ed25519, x25519 } from "@noble/curves/ed25519.js";
import { hkdf } from "@noble/hashes/hkdf.js";
import { hmac } from "@noble/hashes/hmac.js";
import { sha256 } from "@noble/hashes/sha2.js";
import { runInDurableObject } from "cloudflare:test";
import { env, exports } from "cloudflare:workers";
import { expect } from "vitest";
import type { Hub } from "../src/hub";
import { TEST_SETUP_TOKEN } from "./constants";

export const BASE = "https://hub.example.test";

const te = new TextEncoder();

export function b64(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function unb64(text: string): Uint8Array {
  const std = text.replace(/-/g, "+").replace(/_/g, "/");
  return Uint8Array.from(atob(std + "=".repeat((4 - (std.length % 4)) % 4)), (c) => c.charCodeAt(0));
}

function cat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

function lp(x: Uint8Array | string): Uint8Array {
  const bytes = typeof x === "string" ? te.encode(x) : x;
  const len = new Uint8Array(4);
  new DataView(len.buffer).setUint32(0, bytes.length);
  return cat(len, bytes);
}

function u64(n: number): Uint8Array {
  const out = new Uint8Array(8);
  new DataView(out.buffer).setBigUint64(0, BigInt(n));
  return out;
}

export interface ApiOptions {
  token?: string;
  body?: unknown;
  rawBody?: BodyInit;
  headers?: Record<string, string>;
}

export function api(method: string, path: string, opts: ApiOptions = {}): Promise<Response> {
  const headers = new Headers(opts.headers);
  if (opts.token) headers.set("Authorization", `Bearer ${opts.token}`);
  let body: BodyInit | undefined = opts.rawBody;
  if (opts.body !== undefined) {
    headers.set("Content-Type", "application/json");
    body = JSON.stringify(opts.body);
  }
  return exports.default.fetch(new Request(BASE + path, { method, headers, body }));
}

/** Response bodies are asserted field by field, so tests read them loosely. */
export type Json = any;

export async function call(method: string, path: string, opts: ApiOptions = {}, status = 200): Promise<Json> {
  const res = await api(method, path, opts);
  const body = (await res.json()) as Json;
  expect({ status: res.status, body }).toMatchObject({ status });
  return body;
}

export async function expectError(res: Response | Promise<Response>, status: number, code: string): Promise<Json> {
  const r = await res;
  const body = (await r.json()) as Json;
  expect(r.status).toBe(status);
  expect(body.error.code).toBe(code);
  expect(typeof body.error.message).toBe("string");
  return body;
}

let lastMs = 0;
let counter = 0;
/** Monotonic UUIDv7 (RFC 9562 §6.2 method 1: 12-bit counter in rand_a). */
export function uuidv7(): string {
  const ms = Date.now();
  if (ms > lastMs) {
    lastMs = ms;
    counter = 0;
  } else {
    counter++;
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  new DataView(bytes.buffer).setUint32(0, Math.floor(lastMs / 2 ** 16));
  new DataView(bytes.buffer).setUint16(4, lastMs % 2 ** 16);
  bytes[6] = 0x70 | ((counter >> 8) & 0x0f);
  bytes[7] = counter & 0xff;
  bytes[8] = 0x80 | (bytes[8]! & 0x3f);
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export const GROUP_KEY = crypto.getRandomValues(new Uint8Array(32));

export interface SealOptions {
  to: string[];
  collapse?: string;
  ttl_s?: number;
  msg_id?: string;
  payload?: Uint8Array;
  kv?: number;
}

export class TestDevice {
  readonly signSk = ed25519.utils.randomSecretKey();
  readonly signPub = ed25519.getPublicKey(this.signSk);
  readonly kxPub = x25519.getPublicKey(x25519.utils.randomSecretKey());
  readonly id = "dev_" + b64(sha256(this.signPub).slice(0, 16));
  token = "";

  get keys() {
    return { sign_pub: b64(this.signPub), kx_pub: b64(this.kxPub) };
  }

  /** SPEC §2.3 seal + §3 envelope header layout. */
  seal(opts: SealOptions): Json {
    const v = 1;
    const kv = opts.kv ?? 1;
    const msgId = opts.msg_id ?? uuidv7();
    const ttl = opts.ttl_s ?? 3600;
    const salt = crypto.getRandomValues(new Uint8Array(32));
    const toJoined = cat(...opts.to.map((id) => lp(id)));
    const aad = cat(
      lp("carom/v1/env"),
      lp(u64(v)),
      lp(msgId),
      lp(this.id),
      lp(toJoined),
      lp(opts.collapse ?? ""),
      lp(u64(ttl)),
      lp(u64(kv)),
      lp(salt),
    );
    const key = hkdf(sha256, GROUP_KEY, salt, te.encode("carom/v1/msg"), 32);
    const pt = opts.payload ?? te.encode(JSON.stringify({ type: "clipboard.set@1", ts: Date.now(), body: { text: "hi" } }));
    const ct = chacha20poly1305(key, new Uint8Array(12), aad).encrypt(pt);
    const sig = ed25519.sign(cat(aad, lp(ct)), this.signSk);
    return {
      v,
      msg_id: msgId,
      from: this.id,
      to: opts.to,
      ...(opts.collapse === undefined ? {} : { collapse: opts.collapse }),
      ttl_s: ttl,
      kv,
      salt: b64(salt),
      ct: b64(ct),
      sig: b64(sig),
    };
  }

  /** SPEC §5.1 sealed record. */
  record(updated = Date.now()): Json {
    const kv = 1;
    const salt = crypto.getRandomValues(new Uint8Array(32));
    const aad = cat(lp("carom/v1/rec"), lp(this.id), lp(u64(updated)), lp(u64(kv)), lp(salt));
    const key = hkdf(sha256, GROUP_KEY, salt, te.encode("carom/v1/msg"), 32);
    const ct = chacha20poly1305(key, new Uint8Array(12), aad).encrypt(te.encode(JSON.stringify({ id: this.id, name: "test" })));
    const sig = ed25519.sign(cat(aad, lp(ct)), this.signSk);
    return { id: this.id, updated, kv, salt: b64(salt), ct: b64(ct), sig: b64(sig) };
  }

  /** SPEC §8 step 2: mac and pop over the pairing request. */
  pairRequest(pairId: string, pairSecret: Uint8Array): Json {
    const kPair = hkdf(sha256, pairSecret, new Uint8Array(0), te.encode("carom/v1/pair"), 32);
    const msg = cat(lp("carom/v1/pair-req"), lp(pairId), lp(this.signPub), lp(this.kxPub));
    return { pair_id: pairId, ...this.keys, mac: b64(hmac(sha256, kPair, msg)), pop: b64(ed25519.sign(msg, this.signSk)) };
  }
}

export function setupTokenBody(): { setup_token: string } {
  return { setup_token: TEST_SETUP_TOKEN };
}

export async function setupFirstDevice(): Promise<TestDevice> {
  const device = new TestDevice();
  const res = await call("POST", "/v1/setup", { body: { ...setupTokenBody(), ...device.keys } });
  expect(res.device_id).toBe(device.id);
  device.token = res.token;
  return device;
}

export const FAKE_BOX = { epk: b64(new Uint8Array(32).fill(7)), ct: b64(new Uint8Array(64).fill(9)) };

/** Runs the full pairing flow through `offerer` and returns the activated device. */
export async function pairDevice(offerer: TestDevice): Promise<TestDevice> {
  const device = new TestDevice();
  const { pair_id } = await call("POST", "/v1/pair/offer", { token: offerer.token, body: {} });
  const accepted = await call("POST", "/v1/pair/accept", { body: device.pairRequest(pair_id, crypto.getRandomValues(new Uint8Array(32))) });
  device.token = accepted.token;
  await call("POST", `/v1/pair/${pair_id}/welcome`, { token: offerer.token, body: { box: FAKE_BOX } });
  return device;
}

export async function send(from: TestDevice, opts: SealOptions): Promise<Json> {
  return call("POST", "/v1/messages", { token: from.token, body: { envelope: from.seal(opts) } });
}

export function hubStub(): DurableObjectStub<Hub> {
  return env.HUB.get(env.HUB.idFromName("hub")) as DurableObjectStub<Hub>;
}

/** Runs SQL directly against the Hub's database (for time travel and inspection). */
export function hubSql<T extends Record<string, SqlStorageValue>>(query: string, ...args: SqlStorageValue[]): Promise<T[]> {
  return runInDurableObject(hubStub(), (_hub: Hub, state) => state.storage.sql.exec<T>(query, ...args).toArray());
}

/**
 * Queues `count` messages from `from` to `to` straight in SQLite, much faster than posting them.
 * The Hub never parses stored envelopes, so any JSON works. Returns the new seqs.
 */
export async function fillQueue(from: TestDevice, to: string[], count: number, envelope = "{}"): Promise<number[]> {
  const [row] = await hubSql<{ value: string }>("SELECT value FROM meta WHERE key = 'last_seq'");
  const start = Number(row?.value ?? 0);
  await hubSql(
    `WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?)
     INSERT INTO messages (seq, sender, msg_id, collapse, recipients, envelope, expires_at)
     SELECT ? + i, ?, 'fill-' || (? + i), NULL, ?, ?, ? FROM n`,
    count,
    start,
    from.id,
    start,
    JSON.stringify(to),
    envelope,
    Date.now() + 3600_000,
  );
  await hubSql("INSERT OR REPLACE INTO meta (key, value) VALUES ('last_seq', ?)", String(start + count));
  return Array.from({ length: count }, (_, i) => start + i + 1);
}

/** Wipes the Hub through the same path as POST /v1/reset, bypassing lockouts. */
export async function wipeHub(): Promise<void> {
  await hubSql("DELETE FROM lockouts");
  const res = await api("POST", "/v1/reset", { body: { ...setupTokenBody(), confirm: "erase everything" } });
  expect(res.status).toBe(200);
}

export interface SocketClient {
  ws: WebSocket;
  frames: Json[];
  next(predicate?: (f: Json) => boolean): Promise<Json>;
  closed: Promise<CloseEvent>;
}

export async function connect(device: TestDevice): Promise<SocketClient> {
  const res = await api("GET", "/v1/ws", { token: device.token, headers: { Upgrade: "websocket" } });
  expect(res.status).toBe(101);
  const ws = res.webSocket!;
  const frames: Json[] = [];
  const waiters: Array<() => void> = [];
  ws.addEventListener("message", (e) => {
    frames.push(typeof e.data === "string" ? (e.data === "pong" ? "pong" : JSON.parse(e.data)) : e.data);
    for (const w of waiters.splice(0)) w();
  });
  const closed = new Promise<CloseEvent>((resolve) => ws.addEventListener("close", resolve));
  ws.accept();
  let consumed = 0;
  return {
    ws,
    frames,
    closed,
    async next(predicate = () => true) {
      for (;;) {
        while (consumed < frames.length) {
          const f = frames[consumed++];
          if (predicate(f)) return f;
        }
        await new Promise<void>((resolve, reject) => {
          const timer = setTimeout(() => reject(new Error("timed out waiting for a frame")), 2000);
          waiters.push(() => {
            clearTimeout(timer);
            resolve();
          });
        });
      }
    },
  };
}

/** Sends `ping` and waits for `pong`, so every frame sent before it has been handled. */
export async function roundTrip(socket: SocketClient): Promise<void> {
  socket.ws.send("ping");
  await socket.next((f: Json) => f === "pong");
}

export interface SqlCost {
  rowsRead: number;
  rowsWritten: number;
}

/**
 * Runs `fn` while counting rows read and written by every statement the Hub executes, using the
 * SQL cursors' own rowsRead / rowsWritten counters.
 */
export async function measureSql(fn: () => Promise<unknown>): Promise<SqlCost> {
  const cursors: SqlStorageCursor<Record<string, SqlStorageValue>>[] = [];
  const restore = await runInDurableObject(hubStub(), (_hub: Hub, state) => {
    const proto = Object.getPrototypeOf(state.storage.sql) as SqlStorage;
    const original = proto.exec;
    proto.exec = function (this: SqlStorage, query: string, ...args: SqlStorageValue[]) {
      const cursor = original.call(this, query, ...args);
      cursors.push(cursor);
      return cursor;
    } as SqlStorage["exec"];
    return () => {
      proto.exec = original;
    };
  });
  try {
    await fn();
  } finally {
    await runInDurableObject(hubStub(), () => restore());
  }
  let rowsRead = 0;
  let rowsWritten = 0;
  for (const c of cursors) {
    rowsRead += c.rowsRead;
    rowsWritten += c.rowsWritten;
  }
  return { rowsRead, rowsWritten };
}

/**
 * Sends an authenticated request straight to the Hub with a body that stays open until
 * `release()` is called, so other requests run while the handler is still awaiting the body.
 */
export async function heldRequest(device: TestDevice, method: string, path: string, body: unknown): Promise<{ release(): Promise<Response> }> {
  const json = te.encode(JSON.stringify(body));
  const stream = new TransformStream<Uint8Array, Uint8Array>();
  const writer = stream.writable.getWriter();
  const response = hubStub().fetch(
    new Request(BASE + path, {
      method,
      headers: { "x-carom-device": device.id, "x-carom-token-hash": b64(sha256(te.encode(device.token))), "content-type": "application/json" },
      body: stream.readable,
    }),
  );
  void writer.write(json.slice(0, 1));
  // Let the Hub authenticate the caller and start awaiting the rest of the body.
  await new Promise((resolve) => setTimeout(resolve, 50));
  return {
    async release() {
      await writer.write(json.slice(1));
      await writer.close();
      return response;
    },
  };
}

export function filled(length: number, value: number): Uint8Array {
  return new Uint8Array(length).fill(value);
}

/** b64(sha256(ct)): the id a blob is stored under (SPEC §11.2). */
export function blobId(bytes: Uint8Array): string {
  return b64(sha256(bytes));
}

/** PUT /v1/blobs/:id with raw bytes; `id` defaults to the content hash. */
export function putBlob(device: TestDevice, bytes: Uint8Array, query = "ttl_s=3600", id = blobId(bytes)): Promise<Response> {
  return api("PUT", `/v1/blobs/${id}?${query}`, {
    token: device.token,
    rawBody: bytes,
    headers: { "Content-Type": "application/octet-stream" },
  });
}
