// Blob routes (SPEC §6, §11): the Hub checks hash, size, quota and expiry, never the content.

import { runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import type { Hub } from "../src/hub";
import { BLOB_QUOTA_BYTES } from "../src/handlers/blobs";
import { BLOB_BODY_LIMIT } from "../src/routes";
import {
  api,
  b64,
  blobId,
  call,
  expectError,
  filled,
  hubSql,
  hubStub,
  pairDevice,
  putBlob,
  send,
  setupFirstDevice,
  TestDevice,
  uuidv7,
  wipeHub,
  type Json,
} from "./helpers";

beforeEach(wipeHub);

/** Stored blob ids (sorted) and bytes, asserting meta.blob_bytes equals the table total. */
async function stored(): Promise<{ ids: string[]; bytes: number }> {
  const rows = await hubSql<{ id: string; size: number }>("SELECT id, size FROM blobs ORDER BY id");
  const [meta] = await hubSql<{ value: string }>("SELECT value FROM meta WHERE key = 'blob_bytes'");
  const bytes = Number(meta?.value ?? 0);
  expect(bytes).toBe(rows.reduce((n, r) => n + r.size, 0));
  return { ids: rows.map((r) => r.id), bytes };
}

async function json(res: Response | Promise<Response>): Promise<Json> {
  return (await res).json();
}

describe("PUT, HEAD and GET /v1/blobs/:id", () => {
  it("stores ciphertext under its hash and serves it to every active device", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    const data = filled(1_000, 1);
    const before = Date.now();
    const res = await putBlob(a, data);
    expect(res.status).toBe(201);
    const body = (await res.json()) as Json;
    expect(body).toEqual({ id: blobId(data), size: 1_000, expires_at: expect.any(Number) });
    expect(body.expires_at).toBeGreaterThanOrEqual(before + 3_600_000);
    expect(body.expires_at).toBeLessThanOrEqual(Date.now() + 3_600_000);

    const got = await api("GET", `/v1/blobs/${blobId(data)}`, { token: b.token });
    expect(got.status).toBe(200);
    expect(got.headers.get("content-type")).toBe("application/octet-stream");
    expect(got.headers.get("x-expires-at")).toBe(String(body.expires_at));
    expect(new Uint8Array(await got.arrayBuffer())).toEqual(data);

    const head = await api("HEAD", `/v1/blobs/${blobId(data)}`, { token: b.token });
    expect(head.status).toBe(200);
    expect(head.headers.get("x-expires-at")).toBe(String(body.expires_at));
    expect(await stored()).toEqual({ ids: [blobId(data)], bytes: 1_000 });
  });

  it("answers 404 for unknown blobs and for expired ones before the alarm runs", async () => {
    const a = await setupFirstDevice();
    const data = filled(64, 2);
    await expectError(api("GET", `/v1/blobs/${blobId(data)}`, { token: a.token }), 404, "not_found");
    expect((await api("HEAD", `/v1/blobs/${blobId(data)}`, { token: a.token })).status).toBe(404);
    expect((await putBlob(a, data)).status).toBe(201);
    await hubSql("UPDATE blobs SET expires_at = ?", Date.now() - 1);
    await expectError(api("GET", `/v1/blobs/${blobId(data)}`, { token: a.token }), 404, "not_found");
    expect((await api("HEAD", `/v1/blobs/${blobId(data)}`, { token: a.token })).status).toBe(404);
  });

  it("rejects a body that does not hash to the id, and malformed parameters", async () => {
    const a = await setupFirstDevice();
    const data = filled(100, 3);
    await expectError(putBlob(a, data, "ttl_s=3600", blobId(filled(100, 4))), 400, "bad_request");
    await expectError(putBlob(a, data, "ttl_s=3600", "abc"), 400, "bad_request");
    await expectError(putBlob(a, data, ""), 400, "bad_request");
    await expectError(putBlob(a, data, "ttl_s=-5"), 400, "bad_request");
    await expectError(putBlob(a, data, `ttl_s=60&msg_id=${uuidv7().toUpperCase()}`), 400, "bad_request");
    await expectError(putBlob(a, new Uint8Array(0)), 400, "bad_request");
    expect((await api("HEAD", `/v1/blobs/${blobId(data)}?touch=1`, { token: a.token })).status).toBe(400);
    expect((await stored()).ids).toEqual([]);
  });

  it("accepts up to 1 MiB + 1 KiB and refuses more with 413", async () => {
    const a = await setupFirstDevice();
    expect((await putBlob(a, filled(BLOB_BODY_LIMIT, 5))).status).toBe(201);
    await expectError(putBlob(a, filled(BLOB_BODY_LIMIT + 1, 5)), 413, "too_large");
    expect((await stored()).bytes).toBe(BLOB_BODY_LIMIT);
  });

  it("clamps ttl_s to 60..604800", async () => {
    const a = await setupFirstDevice();
    const short = await json(putBlob(a, filled(10, 6), "ttl_s=1"));
    const long = await json(putBlob(a, filled(10, 7), "ttl_s=999999999"));
    const now = Date.now();
    expect(short.expires_at).toBeGreaterThan(now + 50_000);
    expect(short.expires_at).toBeLessThanOrEqual(now + 60_000);
    expect(long.expires_at).toBeGreaterThan(now + 604_700_000);
    expect(long.expires_at).toBeLessThanOrEqual(now + 604_800_000);
  });

  it("an idempotent PUT answers 200, keeps the later expiry and detaches a second message", async () => {
    const a = await setupFirstDevice();
    const data = filled(500, 8);
    const firstMsg = uuidv7();
    const first = await json(putBlob(a, data, `ttl_s=3600&msg_id=${firstMsg}`));
    const shorter = await putBlob(a, data, `ttl_s=60&msg_id=${firstMsg}`);
    expect(shorter.status).toBe(200);
    expect(await shorter.json()).toEqual({ id: blobId(data), size: 500, expires_at: first.expires_at });
    expect(await hubSql("SELECT msg_id FROM blobs")).toEqual([{ msg_id: firstMsg }]);

    // Uploaded again for another message: superseding the first one must no longer delete it.
    const longer = await putBlob(a, data, `ttl_s=7200&msg_id=${uuidv7()}`);
    expect(longer.status).toBe(200);
    expect(((await longer.json()) as Json).expires_at).toBeGreaterThan(first.expires_at);
    expect(await hubSql("SELECT msg_id FROM blobs")).toEqual([{ msg_id: null }]);
    expect(await stored()).toEqual({ ids: [blobId(data)], bytes: 500 });
  });

  it("HEAD with touch=1 extends expiry, plain HEAD does not", async () => {
    const a = await setupFirstDevice();
    const data = filled(50, 9);
    const { expires_at } = await json(putBlob(a, data, "ttl_s=60"));
    const plain = await api("HEAD", `/v1/blobs/${blobId(data)}?ttl_s=7200`, { token: a.token });
    expect(plain.headers.get("x-expires-at")).toBe(String(expires_at));
    const touched = await api("HEAD", `/v1/blobs/${blobId(data)}?touch=1&ttl_s=7200`, { token: a.token });
    expect(touched.status).toBe(200);
    expect(Number(touched.headers.get("x-expires-at"))).toBeGreaterThan(expires_at + 7_000_000);
    const [row] = await hubSql<{ expires_at: number }>("SELECT expires_at FROM blobs");
    expect(String(row!.expires_at)).toBe(touched.headers.get("x-expires-at"));
  });

  it("refuses a new upload past the 256 MiB quota with 429 quota, but accepts re-uploads", async () => {
    const a = await setupFirstDevice();
    const kept = filled(100, 10);
    expect((await putBlob(a, kept)).status).toBe(201);
    // Pretend the store is almost full instead of uploading 256 MiB.
    await hubSql("UPDATE meta SET value = ? WHERE key = 'blob_bytes'", String(BLOB_QUOTA_BYTES - 200));
    const body = await expectError(putBlob(a, filled(201, 11)), 429, "rate_limited");
    expect(body.quota).toBe(true);
    expect((await putBlob(a, filled(200, 12))).status).toBe(201);
    await expectError(putBlob(a, filled(1, 13)), 429, "rate_limited");
    expect((await putBlob(a, kept)).status).toBe(200);
  });

  it("refuses pending devices with 403", async () => {
    const a = await setupFirstDevice();
    const { pair_id } = await call("POST", "/v1/pair/offer", { token: a.token, body: {} });
    const pending = new TestDevice();
    pending.token = (await call("POST", "/v1/pair/accept", { body: pending.pairRequest(pair_id, new Uint8Array(32)) })).token;
    const data = filled(10, 14);
    expect((await putBlob(a, data)).status).toBe(201);
    await expectError(putBlob(pending, filled(10, 15)), 403, "forbidden");
    await expectError(api("GET", `/v1/blobs/${blobId(data)}`, { token: pending.token }), 403, "forbidden");
    expect((await api("HEAD", `/v1/blobs/${blobId(data)}`, { token: pending.token })).status).toBe(403);
    expect((await stored()).ids).toEqual([blobId(data)]);
  });
});

const COLLAPSE = b64(new Uint8Array(16).fill(3));

describe("blob lifetime", () => {
  it("is part of the single alarm and deleted by it once expired", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    await send(a, { to: [b.id], ttl_s: 3600 });
    const soon = filled(100, 16);
    const later = filled(300, 17);
    await putBlob(a, later, "ttl_s=3600");
    await putBlob(a, soon, "ttl_s=60");
    // The alarm follows the earliest expiry of messages, pairings and blobs.
    const alarm = await runInDurableObject(hubStub(), (_hub: Hub, state) => state.storage.getAlarm());
    expect(alarm).not.toBeNull();
    expect(alarm!).toBeLessThanOrEqual(Date.now() + 60_000);

    await hubSql("UPDATE blobs SET expires_at = ? WHERE id = ?", Date.now() - 1, blobId(soon));
    expect(await runDurableObjectAlarm(hubStub())).toBe(true);
    expect(await stored()).toEqual({ ids: [blobId(later)], bytes: 300 });
  });

  it("superseding a message deletes only the blobs uploaded with its msg_id", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    const olderId = uuidv7();
    const oldId = uuidv7();
    const oldImage = filled(100, 20);
    const icon = filled(110, 21);
    const othersBlob = filled(120, 22);
    await putBlob(a, oldImage, `ttl_s=3600&msg_id=${oldId}`);
    // Convergent icons carry no msg_id.
    await putBlob(a, icon);
    // Same msg_id string from another device: not a's message.
    await putBlob(b, othersBlob, `ttl_s=3600&msg_id=${oldId}`);
    await send(a, { to: [b.id], collapse: COLLAPSE, msg_id: oldId });

    const newId = uuidv7();
    const newImage = filled(130, 23);
    await putBlob(a, newImage, `ttl_s=3600&msg_id=${newId}`);
    await send(a, { to: [b.id], collapse: COLLAPSE, msg_id: newId });
    const kept = [blobId(icon), blobId(othersBlob), blobId(newImage)].sort();
    expect(await stored()).toEqual({ ids: kept, bytes: 360 });

    // A late retry of an older msg_id is superseded and deletes nothing.
    expect(await send(a, { to: [b.id], collapse: COLLAPSE, msg_id: olderId })).toMatchObject({ seq: null, superseded: true });
    expect((await stored()).ids).toEqual(kept);
  });

  it("revoking a device deletes every blob it uploaded", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    const mine = filled(100, 30);
    await putBlob(a, mine);
    await putBlob(b, filled(200, 31));
    await putBlob(b, filled(300, 32), `ttl_s=3600&msg_id=${uuidv7()}`);
    await call("DELETE", `/v1/devices/${b.id}`, { token: a.token });
    expect(await stored()).toEqual({ ids: [blobId(mine)], bytes: 100 });
  });

  it("a reset wipes every blob and the byte counter", async () => {
    const a = await setupFirstDevice();
    await putBlob(a, filled(100, 40));
    await wipeHub();
    expect(await hubSql("SELECT id FROM blobs")).toEqual([]);
    expect(await hubSql("SELECT value FROM meta WHERE key = 'blob_bytes'")).toEqual([]);
  });
});
