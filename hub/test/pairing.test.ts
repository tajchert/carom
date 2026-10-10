import { runDurableObjectAlarm } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import {
  FAKE_BOX,
  api,
  call,
  connect,
  expectError,
  hubSql,
  hubStub,
  setupFirstDevice,
  TestDevice,
  wipeHub,
  type Json,
} from "./helpers";

beforeEach(wipeHub);

const secret = () => crypto.getRandomValues(new Uint8Array(32));

describe("pairing", () => {
  it("runs offer -> accept -> pair frame -> status -> welcome -> active", async () => {
    const a = await setupFirstDevice();
    const socket = await connect(a);
    const offer = await call("POST", "/v1/pair/offer", { token: a.token, body: {} });
    expect(offer.pair_id).toMatch(/^[A-Za-z0-9_-]{22}$/);
    expect(offer.expires_at).toBeGreaterThan(Date.now() + 4 * 60 * 1000);
    expect(await call("GET", `/v1/pair/${offer.pair_id}`, { token: a.token })).toEqual({ status: "waiting" });

    const b = new TestDevice();
    const request = b.pairRequest(offer.pair_id, secret());
    const accepted = await call("POST", "/v1/pair/accept", { body: request });
    expect(accepted.device_id).toBe(b.id);
    b.token = accepted.token;

    const candidate = { device_id: b.id, sign_pub: request.sign_pub, kx_pub: request.kx_pub, mac: request.mac, pop: request.pop };
    expect(await socket.next((f: Json) => f.t === "pair")).toEqual({ t: "pair", pair_id: offer.pair_id, candidate });
    expect(await call("GET", `/v1/pair/${offer.pair_id}`, { token: a.token })).toEqual({ status: "accepted", candidate });

    // Pending: only the welcome fetch is allowed, and it 404s until the welcome exists.
    await expectError(api("GET", `/v1/pair/${offer.pair_id}/welcome`, { token: b.token }), 404, "not_found");
    await expectError(api("GET", "/v1/devices", { token: b.token }), 403, "forbidden");
    await expectError(api("GET", "/v1/messages", { token: b.token }), 403, "forbidden");
    await expectError(api("GET", "/v1/ws", { token: b.token, headers: { Upgrade: "websocket" } }), 403, "forbidden");
    await expectError(api("POST", "/v1/pair/offer", { token: b.token, body: {} }), 403, "forbidden");
    const listed = await call("GET", "/v1/devices", { token: a.token });
    expect(listed.devices.map((d: Json) => d.id)).toEqual([a.id]);

    await call("POST", `/v1/pair/${offer.pair_id}/welcome`, { token: a.token, body: { box: FAKE_BOX } });
    expect(await socket.next((f: Json) => f.t === "dir")).toEqual({ t: "dir" });
    expect(await call("GET", `/v1/pair/${offer.pair_id}/welcome`, { token: b.token })).toEqual({ box: FAKE_BOX });
    expect((await call("GET", `/v1/pair/${offer.pair_id}`, { token: a.token })).status).toBe("done");
    const after = await call("GET", "/v1/devices", { token: b.token });
    expect(after.devices.map((d: Json) => d.id).sort()).toEqual([a.id, b.id].sort());
    socket.ws.close();
  });

  it("is single-use", async () => {
    const a = await setupFirstDevice();
    const { pair_id } = await call("POST", "/v1/pair/offer", { token: a.token, body: {} });
    await call("POST", "/v1/pair/accept", { body: new TestDevice().pairRequest(pair_id, secret()) });
    await expectError(api("POST", "/v1/pair/accept", { body: new TestDevice().pairRequest(pair_id, secret()) }), 409, "conflict");
    await expectError(api("POST", "/v1/pair/accept", { body: new TestDevice().pairRequest("A".repeat(22), secret()) }), 404, "not_found");
  });

  it("rejects malformed accepts", async () => {
    const a = await setupFirstDevice();
    const { pair_id } = await call("POST", "/v1/pair/offer", { token: a.token, body: {} });
    const good = new TestDevice().pairRequest(pair_id, secret());
    for (const bad of [{ ...good, mac: "AA" }, { ...good, pop: good.mac }, { ...good, sign_pub: "!" }, { pair_id }]) {
      await expectError(api("POST", "/v1/pair/accept", { body: bad }), 400, "bad_request");
    }
  });

  it("reject deletes the candidate", async () => {
    const a = await setupFirstDevice();
    const { pair_id } = await call("POST", "/v1/pair/offer", { token: a.token, body: {} });
    const b = new TestDevice();
    b.token = (await call("POST", "/v1/pair/accept", { body: b.pairRequest(pair_id, secret()) })).token;
    await call("POST", `/v1/pair/${pair_id}/reject`, { token: a.token, body: {} });
    await expectError(api("GET", `/v1/pair/${pair_id}/welcome`, { token: b.token }), 401, "unauthorized");
    await expectError(api("GET", `/v1/pair/${pair_id}`, { token: a.token }), 404, "not_found");
    expect(await hubSql("SELECT id FROM devices WHERE id = ?", b.id)).toEqual([]);
  });

  it("only lets the offerer see or act on its pairing", async () => {
    const a = await setupFirstDevice();
    const { pair_id } = await call("POST", "/v1/pair/offer", { token: a.token, body: {} });
    const b = new TestDevice();
    b.token = (await call("POST", "/v1/pair/accept", { body: b.pairRequest(pair_id, secret()) })).token;
    await call("POST", `/v1/pair/${pair_id}/welcome`, { token: a.token, body: { box: FAKE_BOX } });
    // b is active now but is not the offerer.
    await expectError(api("GET", `/v1/pair/${pair_id}`, { token: b.token }), 404, "not_found");
    await expectError(api("POST", `/v1/pair/${pair_id}/reject`, { token: b.token, body: {} }), 404, "not_found");
    await expectError(api("POST", `/v1/pair/${pair_id}/welcome`, { token: a.token, body: { box: FAKE_BOX } }), 409, "conflict");
    await expectError(api("POST", `/v1/pair/${pair_id}/reject`, { token: a.token, body: {} }), 409, "conflict");
    await expectError(api("GET", `/v1/pair/${pair_id}/welcome`, { token: a.token }), 404, "not_found");
  });

  it("rejects a welcome before accept and a malformed box", async () => {
    const a = await setupFirstDevice();
    const { pair_id } = await call("POST", "/v1/pair/offer", { token: a.token, body: {} });
    await expectError(api("POST", `/v1/pair/${pair_id}/welcome`, { token: a.token, body: { box: FAKE_BOX } }), 409, "conflict");
    await expectError(api("POST", `/v1/pair/${pair_id}/welcome`, { token: a.token, body: { box: { epk: "AA" } } }), 400, "bad_request");
  });

  it("allows at most 5 open offers", async () => {
    const a = await setupFirstDevice();
    for (let i = 0; i < 5; i++) await call("POST", "/v1/pair/offer", { token: a.token, body: {} });
    await expectError(api("POST", "/v1/pair/offer", { token: a.token, body: {} }), 429, "rate_limited");
  });

  it("expires after 5 minutes and deletes the pending device", async () => {
    const a = await setupFirstDevice();
    const { pair_id } = await call("POST", "/v1/pair/offer", { token: a.token, body: {} });
    const b = new TestDevice();
    b.token = (await call("POST", "/v1/pair/accept", { body: b.pairRequest(pair_id, secret()) })).token;
    const waiting = await call("POST", "/v1/pair/offer", { token: a.token, body: {} });

    await hubSql("UPDATE pairings SET expires_at = ?", Date.now() - 1);
    await expectError(api("GET", `/v1/pair/${pair_id}`, { token: a.token }), 410, "gone");
    await expectError(api("POST", "/v1/pair/accept", { body: new TestDevice().pairRequest(waiting.pair_id, secret()) }), 410, "gone");

    expect(await runDurableObjectAlarm(hubStub())).toBe(true);
    expect(await hubSql("SELECT * FROM pairings")).toEqual([]);
    expect(await hubSql("SELECT id FROM devices WHERE id = ?", b.id)).toEqual([]);
    await expectError(api("GET", `/v1/pair/${pair_id}/welcome`, { token: b.token }), 401, "unauthorized");
    // Nothing left to expire: no alarm stays scheduled.
    expect(await runDurableObjectAlarm(hubStub())).toBe(false);
  });

  it("keeps a welcomed device when its pairing expires", async () => {
    const a = await setupFirstDevice();
    const { pair_id } = await call("POST", "/v1/pair/offer", { token: a.token, body: {} });
    const b = new TestDevice();
    b.token = (await call("POST", "/v1/pair/accept", { body: b.pairRequest(pair_id, secret()) })).token;
    await call("POST", `/v1/pair/${pair_id}/welcome`, { token: a.token, body: { box: FAKE_BOX } });
    await hubSql("UPDATE pairings SET expires_at = ?", Date.now() - 1);
    await runDurableObjectAlarm(hubStub());
    await call("GET", "/v1/devices", { token: b.token });
  });

  it("refuses to pair a key that is already a device", async () => {
    const a = await setupFirstDevice();
    const { pair_id } = await call("POST", "/v1/pair/offer", { token: a.token, body: {} });
    await expectError(api("POST", "/v1/pair/accept", { body: a.pairRequest(pair_id, secret()) }), 409, "conflict");
  });
});
