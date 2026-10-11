import { runDurableObjectAlarm } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import {
  api,
  b64,
  call,
  connect,
  expectError,
  fillQueue,
  hubSql,
  hubStub,
  measureSql,
  pairDevice,
  send,
  setupFirstDevice,
  TestDevice,
  uuidv7,
  wipeHub,
  type Json,
} from "./helpers";

beforeEach(wipeHub);

async function trio() {
  const a = await setupFirstDevice();
  const b = await pairDevice(a);
  const c = await pairDevice(a);
  return { a, b, c };
}

async function inbox(device: TestDevice, query = ""): Promise<Json> {
  return call("GET", `/v1/messages${query}`, { token: device.token });
}

const COLLAPSE = b64(new Uint8Array(16).fill(1));

describe("POST /v1/messages + GET /v1/messages + acks", () => {
  it("delivers to every recipient and keeps the message until all acked", async () => {
    const { a, b, c } = await trio();
    const envelope = a.seal({ to: [b.id, c.id] });
    const posted = await call("POST", "/v1/messages", { token: a.token, body: { envelope } });
    expect(posted).toEqual({ seq: expect.any(Number), duplicate: false, online: [] });

    const forB = await inbox(b);
    expect(forB).toEqual({ messages: [{ seq: posted.seq, envelope }], more: false });
    expect((await inbox(a)).messages).toEqual([]);

    await call("POST", "/v1/acks", { token: b.token, body: { up_to: posted.seq } });
    expect((await inbox(b)).messages).toEqual([]);
    expect((await inbox(c)).messages).toHaveLength(1);
    expect(await hubSql("SELECT seq FROM messages")).toHaveLength(1);

    await call("POST", "/v1/acks", { token: c.token, body: { up_to: posted.seq } });
    // Fully acknowledged: only a tombstone without the envelope is left, until expires_at.
    expect(await hubSql("SELECT seq, envelope FROM messages")).toEqual([{ seq: posted.seq, envelope: null }]);
  });

  it("acks are cumulative and paginate with more", async () => {
    const { a, b } = await trio();
    const seqs: number[] = [];
    for (let i = 0; i < 5; i++) seqs.push((await send(a, { to: [b.id] })).seq);
    const page = await inbox(b, "?limit=2");
    expect(page.messages.map((m: Json) => m.seq)).toEqual(seqs.slice(0, 2));
    expect(page.more).toBe(true);
    await call("POST", "/v1/acks", { token: b.token, body: { up_to: seqs[2] } });
    const rest = await inbox(b);
    expect(rest.messages.map((m: Json) => m.seq)).toEqual(seqs.slice(3));
    expect(rest.more).toBe(false);
    // Acking an old or unknown seq succeeds silently and never moves the cursor back.
    await call("POST", "/v1/acks", { token: b.token, body: { up_to: 0 } });
    await call("POST", "/v1/acks", { token: b.token, body: { up_to: 10 ** 12 } });
    const next = await send(a, { to: [b.id] });
    expect((await inbox(b)).messages.map((m: Json) => m.seq)).toEqual([next.seq]);
  });

  it("never reuses a seq hidden behind a cursor after messages are deleted", async () => {
    const { a, b } = await trio();
    const first = await send(a, { to: [b.id] });
    await call("POST", "/v1/acks", { token: b.token, body: { up_to: first.seq } });
    expect(await hubSql("SELECT seq FROM messages WHERE envelope IS NOT NULL")).toEqual([]);
    const second = await send(a, { to: [b.id] });
    expect(second.seq).toBeGreaterThan(first.seq);
    expect((await inbox(b)).messages.map((m: Json) => m.seq)).toEqual([second.seq]);
  });

  it("validates limit and up_to", async () => {
    const { b } = await trio();
    await expectError(api("GET", "/v1/messages?limit=201", { token: b.token }), 400, "bad_request");
    await expectError(api("GET", "/v1/messages?limit=0", { token: b.token }), 400, "bad_request");
    await expectError(api("GET", "/v1/messages?limit=x", { token: b.token }), 400, "bad_request");
    await expectError(api("POST", "/v1/acks", { token: b.token, body: { up_to: -1 } }), 400, "bad_request");
    await expectError(api("POST", "/v1/acks", { token: b.token, body: { up_to: "1" } }), 400, "bad_request");
  });

  it("returns the original seq for a duplicate msg_id", async () => {
    const { a, b } = await trio();
    const envelope = a.seal({ to: [b.id] });
    const first = await call("POST", "/v1/messages", { token: a.token, body: { envelope } });
    // A retry re-sealed under the same msg_id is still a duplicate.
    const again = await call("POST", "/v1/messages", { token: a.token, body: { envelope: a.seal({ to: [b.id], msg_id: envelope.msg_id }) } });
    expect(again).toEqual({ seq: first.seq, duplicate: true, online: [] });
    expect((await inbox(b)).messages).toHaveLength(1);
  });

  it("answers 409 with the inactive recipients", async () => {
    const { a, b } = await trio();
    const ghost = new TestDevice();
    const body = await expectError(api("POST", "/v1/messages", { token: a.token, body: { envelope: a.seal({ to: [b.id, ghost.id] }) } }), 409, "conflict");
    expect(body.inactive).toEqual([ghost.id]);
    expect(await hubSql("SELECT seq FROM messages")).toEqual([]);
  });

  it("treats pending devices as inactive", async () => {
    const a = await setupFirstDevice();
    const { pair_id } = await call("POST", "/v1/pair/offer", { token: a.token, body: {} });
    const pending = new TestDevice();
    await call("POST", "/v1/pair/accept", { body: pending.pairRequest(pair_id, new Uint8Array(32)) });
    const body = await expectError(api("POST", "/v1/messages", { token: a.token, body: { envelope: a.seal({ to: [pending.id] }) } }), 409, "conflict");
    expect(body.inactive).toEqual([pending.id]);
  });

  it("rejects a bad signature and a forged from", async () => {
    const { a, b, c } = await trio();
    const envelope = a.seal({ to: [b.id] });
    const tampered = { ...envelope, ttl_s: envelope.ttl_s + 1 };
    await expectError(api("POST", "/v1/messages", { token: a.token, body: { envelope: tampered } }), 400, "bad_request");
    const reordered = a.seal({ to: [b.id, c.id] });
    await expectError(api("POST", "/v1/messages", { token: a.token, body: { envelope: { ...reordered, to: [c.id, b.id] } } }), 400, "bad_request");
    // Signed by a, sent with c's token.
    await expectError(api("POST", "/v1/messages", { token: c.token, body: { envelope } }), 403, "forbidden");
    // Claims to be c but is signed by a.
    await expectError(api("POST", "/v1/messages", { token: c.token, body: { envelope: { ...envelope, from: c.id } } }), 400, "bad_request");
  });

  it("enforces shape, ttl and size limits", async () => {
    const { a, b } = await trio();
    const ok = a.seal({ to: [b.id] });
    const bad: Json[] = [
      { ...ok, v: 2 },
      { ...ok, msg_id: ok.msg_id.toUpperCase() },
      { ...ok, msg_id: "01928f3c-7b1e-4c4a-9d2f-5e8a1b2c3d4e" },
      { ...ok, to: [] },
      { ...ok, to: [b.id, b.id] },
      { ...ok, to: [a.id] },
      { ...ok, to: Array.from({ length: 33 }, () => new TestDevice().id) },
      { ...ok, collapse: "short" },
      { ...ok, kv: 0 },
      { ...ok, salt: b64(new Uint8Array(16)) },
      { ...ok, sig: b64(new Uint8Array(63)) },
      { ...ok, extra: true },
      { ...ok, ttl_s: 59 },
      { ...ok, ttl_s: 604801 },
    ];
    for (const envelope of bad) {
      await expectError(api("POST", "/v1/messages", { token: a.token, body: { envelope } }), 400, "bad_request");
    }
    // ttl bounds are inclusive.
    await send(a, { to: [b.id], ttl_s: 60 });
    await send(a, { to: [b.id], ttl_s: 604800 });
    // The plaintext limit is 65 536 bytes.
    await send(a, { to: [b.id], payload: new Uint8Array(65536).fill(32) });
    const tooBig = a.seal({ to: [b.id], payload: new Uint8Array(65537).fill(32) });
    await expectError(api("POST", "/v1/messages", { token: a.token, body: { envelope: tooBig } }), 413, "too_large");
  });
});

describe("collapse", () => {
  it("replaces the older message and every pending delivery of it", async () => {
    const { a, b, c } = await trio();
    const old = await send(a, { to: [b.id, c.id], collapse: COLLAPSE });
    const fresh = await send(a, { to: [b.id], collapse: COLLAPSE });
    expect(fresh.seq).toBeGreaterThan(old.seq);
    expect((await inbox(b)).messages.map((m: Json) => m.seq)).toEqual([fresh.seq]);
    // c was not in the new `to`, but its delivery of the old message is gone too.
    expect((await inbox(c)).messages).toEqual([]);
    // Acking the deleted seq succeeds silently.
    await call("POST", "/v1/acks", { token: c.token, body: { up_to: old.seq } });
  });

  it("drops a late retry with an older msg_id as superseded", async () => {
    const { a, b } = await trio();
    const olderId = uuidv7();
    const newer = await send(a, { to: [b.id], collapse: COLLAPSE });
    const late = await send(a, { to: [b.id], collapse: COLLAPSE, msg_id: olderId });
    expect(late).toMatchObject({ seq: null, superseded: true });
    expect((await inbox(b)).messages.map((m: Json) => m.seq)).toEqual([newer.seq]);
  });

  it("keeps collapse slots separate per sender and key", async () => {
    const { a, b, c } = await trio();
    await send(a, { to: [b.id], collapse: COLLAPSE });
    await send(c, { to: [b.id], collapse: COLLAPSE });
    await send(a, { to: [b.id], collapse: b64(new Uint8Array(16).fill(2)) });
    await send(a, { to: [b.id] });
    expect((await inbox(b)).messages).toHaveLength(4);
  });
});

describe("retention", () => {
  it("expires messages via the alarm after ttl_s", async () => {
    const { a, b } = await trio();
    await send(a, { to: [b.id], ttl_s: 60 });
    const keep = await send(a, { to: [b.id], ttl_s: 3600 });
    const [earliest] = await hubSql<{ t: number }>("SELECT MIN(expires_at) AS t FROM messages");
    expect(earliest!.t).toBeGreaterThan(Date.now() + 50_000);

    await hubSql("UPDATE messages SET expires_at = ? WHERE seq != ?", Date.now() - 1, keep.seq);
    // Expired messages are hidden even before the alarm runs.
    expect((await inbox(b)).messages.map((m: Json) => m.seq)).toEqual([keep.seq]);
    expect(await runDurableObjectAlarm(hubStub())).toBe(true);
    expect(await hubSql<{ seq: number }>("SELECT seq FROM messages")).toEqual([{ seq: keep.seq }]);
  });

  it("throttles last_seen writes to once per 10 minutes", async () => {
    const { a } = await trio();
    await hubSql("UPDATE devices SET last_seen = ? WHERE id = ?", Date.now() - 5 * 60 * 1000, a.id);
    const [before] = await hubSql<{ last_seen: number }>("SELECT last_seen FROM devices WHERE id = ?", a.id);
    await inbox(a);
    expect(await hubSql("SELECT last_seen FROM devices WHERE id = ?", a.id)).toEqual([before]);
    await hubSql("UPDATE devices SET last_seen = ? WHERE id = ?", Date.now() - 11 * 60 * 1000, a.id);
    await inbox(a);
    const [after] = await hubSql<{ last_seen: number }>("SELECT last_seen FROM devices WHERE id = ?", a.id);
    expect(after!.last_seen).toBeGreaterThan(Date.now() - 60_000);
  });
});

describe("tombstones", () => {
  it("answer duplicate with the original seq after every recipient acked, without redelivering", async () => {
    const { a, b } = await trio();
    const envelope = a.seal({ to: [b.id] });
    const first = await call("POST", "/v1/messages", { token: a.token, body: { envelope } });
    await call("POST", "/v1/acks", { token: b.token, body: { up_to: first.seq } });
    const retry = await call("POST", "/v1/messages", { token: a.token, body: { envelope: a.seal({ to: [b.id], msg_id: envelope.msg_id }) } });
    expect(retry).toEqual({ seq: first.seq, duplicate: true, online: [] });
    expect((await inbox(b)).messages).toEqual([]);
  });

  it("answer superseded for a late retry of an older msg_id after the newer one was acked", async () => {
    const { a, b } = await trio();
    const olderId = uuidv7();
    const newer = await send(a, { to: [b.id], collapse: COLLAPSE });
    await call("POST", "/v1/acks", { token: b.token, body: { up_to: newer.seq } });
    const late = await send(a, { to: [b.id], collapse: COLLAPSE, msg_id: olderId });
    expect(late).toEqual({ seq: null, duplicate: false, superseded: true, online: [] });
    expect((await inbox(b)).messages).toEqual([]);
    // A newer message still replaces the tombstone.
    const fresh = await send(a, { to: [b.id], collapse: COLLAPSE });
    expect((await inbox(b)).messages.map((m: Json) => m.seq)).toEqual([fresh.seq]);
    expect(await hubSql("SELECT seq FROM messages")).toEqual([{ seq: fresh.seq }]);
  });

  it("are dropped by the alarm once they expire", async () => {
    const { a, b } = await trio();
    const posted = await send(a, { to: [b.id] });
    await call("POST", "/v1/acks", { token: b.token, body: { up_to: posted.seq } });
    await hubSql("UPDATE messages SET expires_at = ?", Date.now() - 1);
    expect(await runDurableObjectAlarm(hubStub())).toBe(true);
    expect(await hubSql("SELECT seq FROM messages")).toEqual([]);
  });
});

describe("pending limit", () => {
  it("refuses a post to a recipient with 1000 pending messages with 429 and the full ids", async () => {
    const { a, b, c } = await trio();
    const [oldest] = await fillQueue(c, [b.id], 999);
    await send(a, { to: [b.id] });
    const body = await expectError(api("POST", "/v1/messages", { token: a.token, body: { envelope: a.seal({ to: [c.id, b.id] }) } }), 429, "rate_limited");
    expect(body.full).toEqual([b.id]);
    await send(a, { to: [c.id] });
    // Acknowledged and expired messages no longer count.
    await call("POST", "/v1/acks", { token: b.token, body: { up_to: oldest } });
    await send(a, { to: [b.id] });
    await expectError(api("POST", "/v1/messages", { token: a.token, body: { envelope: a.seal({ to: [b.id] }) } }), 429, "rate_limited");
    await hubSql("UPDATE messages SET expires_at = ? WHERE seq = ?", Date.now() - 1, oldest! + 1);
    await send(a, { to: [b.id] });
  });

  it("lets a collapse replace a pending message of a full recipient", async () => {
    const { a, b } = await trio();
    await send(a, { to: [b.id], collapse: COLLAPSE });
    await fillQueue(a, [b.id], 999);
    await send(a, { to: [b.id], collapse: COLLAPSE });
  });
});

describe("SQL cost", () => {
  it("reads only the acked range on each ack, not the whole queue", async () => {
    const { a, b, c } = await trio();
    const seqs = await fillQueue(a, [b.id, c.id], 300);
    // c is offline, b acks every message on its own.
    const cost = await measureSql(async () => {
      for (const seq of seqs) await call("POST", "/v1/acks", { token: b.token, body: { up_to: seq } });
    });
    expect(cost.rowsRead / seqs.length).toBeLessThan(20);
  });

  it("does not scan queued messages when posting or when the alarm runs", async () => {
    const { a, b, c } = await trio();
    await fillQueue(a, [c.id], 300);
    // b has seen everything; c is offline with 300 pending messages.
    await call("POST", "/v1/acks", { token: b.token, body: { up_to: 300 } });
    const posting = await measureSql(() => send(a, { to: [b.id] }));
    expect(posting.rowsRead).toBeLessThan(50);
    await hubSql("UPDATE messages SET expires_at = ? WHERE seq = 1", Date.now() - 1);
    const alarm = await measureSql(() => runDurableObjectAlarm(hubStub()));
    expect(alarm.rowsRead).toBeLessThan(50);
    expect(await hubSql("SELECT COUNT(*) AS n FROM messages")).toEqual([{ n: 300 }]);
  });

  it("writes a bounded number of rows per message lifecycle", async () => {
    const { a, b, c } = await trio();
    let seq = 0;
    const post = await measureSql(async () => {
      seq = (await send(a, { to: [b.id, c.id] })).seq;
    });
    const fetch = await measureSql(() => inbox(b));
    const acks = await measureSql(async () => {
      await call("POST", "/v1/acks", { token: b.token, body: { up_to: seq } });
      await call("POST", "/v1/acks", { token: c.token, body: { up_to: seq } });
    });
    await hubSql("UPDATE messages SET expires_at = ?", Date.now() - 1);
    const expiry = await measureSql(() => runDurableObjectAlarm(hubStub()));
    const total = post.rowsWritten + fetch.rowsWritten + acks.rowsWritten + expiry.rowsWritten;
    console.log(JSON.stringify({ rowsWritten: { post: post.rowsWritten, fetch: fetch.rowsWritten, acks: acks.rowsWritten, expiry: expiry.rowsWritten, total } }));
    expect(total).toBeLessThanOrEqual(20);
  });
});

describe("online recipients", () => {
  it("lists the recipients with a live WebSocket in every post response", async () => {
    const { a, b, c } = await trio();
    const socket = await connect(b);
    const envelope = a.seal({ to: [b.id, c.id] });
    const posted = await call("POST", "/v1/messages", { token: a.token, body: { envelope } });
    expect(posted).toEqual({ seq: expect.any(Number), duplicate: false, online: [b.id] });
    const again = await call("POST", "/v1/messages", { token: a.token, body: { envelope } });
    expect(again).toEqual({ seq: posted.seq, duplicate: true, online: [b.id] });

    const newer = await send(a, { to: [b.id, c.id], collapse: COLLAPSE });
    expect(newer.online).toEqual([b.id]);
    const late = await send(a, { to: [b.id, c.id], collapse: COLLAPSE, msg_id: "00000000-0000-7000-8000-000000000000" });
    expect(late).toEqual({ seq: null, duplicate: false, superseded: true, online: [b.id] });

    socket.ws.close();
    await socket.closed;
    await expect.poll(async () => (await send(a, { to: [b.id, c.id] })).online).toEqual([]);
  });
});
