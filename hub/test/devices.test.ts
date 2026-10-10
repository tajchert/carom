import { beforeEach, describe, expect, it } from "vitest";
import {
  BASE,
  api,
  b64,
  call,
  connect,
  expectError,
  hubSql,
  pairDevice,
  send,
  setupFirstDevice,
  TestDevice,
  wipeHub,
  type Json,
} from "./helpers";

beforeEach(wipeHub);

const P256DH = b64(Uint8Array.from({ length: 65 }, (_, i) => (i === 0 ? 4 : i)));
const AUTH = b64(new Uint8Array(16).fill(5));

describe("GET /v1/devices", () => {
  it("lists active devices with their public keys", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    const { devices } = await call("GET", "/v1/devices", { token: b.token });
    expect(devices).toHaveLength(2);
    const entryA = devices.find((d: Json) => d.id === a.id);
    expect(entryA).toEqual({
      id: a.id,
      sign_pub: a.keys.sign_pub,
      kx_pub: a.keys.kx_pub,
      created_at: expect.any(Number),
      last_seen: expect.any(Number),
      has_push: false,
    });
  });
});

describe("PUT /v1/devices/self/record", () => {
  it("stores the sealed record and notifies the others", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    const socket = await connect(b);
    const record = a.record();
    await call("PUT", "/v1/devices/self/record", { token: a.token, body: { record } });
    expect(await socket.next()).toEqual({ t: "dir" });
    const { devices } = await call("GET", "/v1/devices", { token: b.token });
    expect(devices.find((d: Json) => d.id === a.id).record).toEqual(record);
    socket.ws.close();
  });

  it("rejects someone else's record, a bad signature and bad shapes", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    await expectError(api("PUT", "/v1/devices/self/record", { token: a.token, body: { record: b.record() } }), 403, "forbidden");
    const record = a.record();
    await expectError(api("PUT", "/v1/devices/self/record", { token: a.token, body: { record: { ...record, updated: record.updated + 1 } } }), 400, "bad_request");
    await expectError(api("PUT", "/v1/devices/self/record", { token: a.token, body: { record: { ...record, extra: 1 } } }), 400, "bad_request");
    await expectError(api("PUT", "/v1/devices/self/record", { token: a.token, body: {} }), 400, "bad_request");
  });
});

describe("push subscription", () => {
  it("sets and clears has_push", async () => {
    const a = await setupFirstDevice();
    await call("PUT", "/v1/devices/self/push", { token: a.token, body: { endpoint: "https://push.example.com/abc", p256dh: P256DH, auth: AUTH } });
    expect((await call("GET", "/v1/devices", { token: a.token })).devices[0].has_push).toBe(true);
    await call("DELETE", "/v1/devices/self/push", { token: a.token });
    expect((await call("GET", "/v1/devices", { token: a.token })).devices[0].has_push).toBe(false);
  });

  it("requires https, a foreign host and valid keys", async () => {
    const a = await setupFirstDevice();
    const host = new URL(BASE).host;
    for (const body of [
      { endpoint: "http://push.example.com/abc", p256dh: P256DH, auth: AUTH },
      { endpoint: `https://${host}/v1/info`, p256dh: P256DH, auth: AUTH },
      { endpoint: "not a url", p256dh: P256DH, auth: AUTH },
      { endpoint: "https://push.example.com/abc", p256dh: b64(new Uint8Array(65)), auth: AUTH },
      { endpoint: "https://push.example.com/abc", p256dh: P256DH, auth: b64(new Uint8Array(8)) },
    ]) {
      await expectError(api("PUT", "/v1/devices/self/push", { token: a.token, body }), 400, "bad_request");
    }
  });

  it("requires the default port and a DNS name that is not the Hub, an IP literal or localhost", async () => {
    const a = await setupFirstDevice();
    const host = new URL(BASE).hostname;
    for (const endpoint of [
      `https://${host}./v1/info`,
      `https://${host.toUpperCase()}../v1/info`,
      `https://${host}:443/v1/info`,
      "https://push.example.com:8443/abc",
      "https://127.0.0.1/abc",
      "https://0x7f.1/abc",
      "https://10.0.0.1./abc",
      "https://[::1]/abc",
      "https://localhost/abc",
      "https://LocalHost./abc",
      "https://hub.localhost/abc",
    ]) {
      const res = await api("PUT", "/v1/devices/self/push", { token: a.token, body: { endpoint, p256dh: P256DH, auth: AUTH } });
      expect({ endpoint, status: res.status }).toEqual({ endpoint, status: 400 });
    }
    for (const endpoint of ["https://push.example.com:443/abc", "https://Push.Example.com./abc", `https://sub.${host}/abc`]) {
      await call("PUT", "/v1/devices/self/push", { token: a.token, body: { endpoint, p256dh: P256DH, auth: AUTH } });
    }
  });
});

describe("DELETE /v1/devices/:id", () => {
  it("revokes a device: token dead, deliveries dropped, revoked + dir frames", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    const c = await pairDevice(a);
    const socketB = await connect(b);
    const socketC = await connect(c);
    await send(a, { to: [b.id] });
    await send(a, { to: [b.id, c.id] });
    await socketB.next((f: Json) => f.t === "msg");

    await call("DELETE", `/v1/devices/${b.id}`, { token: a.token });
    expect(await socketB.next((f: Json) => f.t === "revoked")).toEqual({ t: "revoked" });
    const closed = await socketB.closed;
    expect(closed.code).toBe(4001);
    expect(await socketC.next((f: Json) => f.t === "dir")).toEqual({ t: "dir" });

    await expectError(api("GET", "/v1/devices", { token: b.token }), 401, "unauthorized");
    const recipients = await hubSql<{ recipients: string }>("SELECT recipients FROM messages WHERE envelope IS NOT NULL");
    expect(recipients.map((r) => JSON.parse(r.recipients))).toEqual([[c.id]]);
    const body = await expectError(api("POST", "/v1/messages", { token: a.token, body: { envelope: a.seal({ to: [b.id] }) } }), 409, "conflict");
    expect(body.inactive).toEqual([b.id]);
    socketC.ws.close();
  });

  it("allows self and 404s unknown ids", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    await expectError(api("DELETE", `/v1/devices/${new TestDevice().id}`, { token: a.token }), 404, "not_found");
    await call("DELETE", "/v1/devices/self", { token: b.token });
    await expectError(api("GET", "/v1/devices", { token: b.token }), 401, "unauthorized");
    expect((await call("GET", "/v1/devices", { token: a.token })).devices).toHaveLength(1);
  });
});
