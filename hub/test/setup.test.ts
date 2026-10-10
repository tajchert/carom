import { beforeEach, describe, expect, it } from "vitest";
import pkg from "../package.json";
import { api, call, expectError, hubSql, setupFirstDevice, setupTokenBody, TestDevice, wipeHub } from "./helpers";

beforeEach(wipeHub);

describe("GET /v1/info", () => {
  it("describes the hub without auth", async () => {
    const info = await call("GET", "/v1/info");
    expect(info).toMatchObject({ name: "carom-hub", protocol: 1, version: pkg.version, setup_done: false, jurisdiction: null });
    const point = Uint8Array.from(atob(info.vapid_public_key.replace(/-/g, "+").replace(/_/g, "/") + "="), (c) => c.charCodeAt(0));
    expect(point.length).toBe(65);
    expect(point[0]).toBe(4);
  });

  it("keeps the VAPID key stable and reports setup_done", async () => {
    const before = await call("GET", "/v1/info");
    await setupFirstDevice();
    const after = await call("GET", "/v1/info");
    expect(after.setup_done).toBe(true);
    expect(after.vapid_public_key).toBe(before.vapid_public_key);
  });
});

describe("POST /v1/setup", () => {
  it("registers the first device once", async () => {
    const device = await setupFirstDevice();
    expect(device.token).toMatch(/^ct1\.dev_[A-Za-z0-9_-]{22}\.[A-Za-z0-9_-]{43}\.[A-Za-z0-9_-]{22}$/);
    const second = new TestDevice();
    await expectError(api("POST", "/v1/setup", { body: { ...setupTokenBody(), ...second.keys } }), 409, "conflict");
    const { devices } = await call("GET", "/v1/devices", { token: device.token });
    expect(devices.map((d: { id: string }) => d.id)).toEqual([device.id]);
  });

  it("rejects a wrong setup token and malformed keys", async () => {
    const device = new TestDevice();
    await expectError(api("POST", "/v1/setup", { body: { setup_token: "x".repeat(40), ...device.keys } }), 403, "forbidden");
    await expectError(api("POST", "/v1/setup", { body: { ...setupTokenBody(), sign_pub: "AAAA", kx_pub: device.keys.kx_pub } }), 400, "bad_request");
    await expectError(api("POST", "/v1/setup", { body: { ...setupTokenBody(), ...device.keys, extra: 1 } }), 400, "bad_request");
    await expectError(api("POST", "/v1/setup", { rawBody: "not json" }), 400, "bad_request");
  });

  it("locks the route for an hour after 10 failures", async () => {
    const device = new TestDevice();
    const wrong = { setup_token: "w".repeat(40), ...device.keys };
    for (let i = 0; i < 10; i++) await expectError(api("POST", "/v1/setup", { body: wrong }), 403, "forbidden");
    // Even the right token is refused while locked.
    await expectError(api("POST", "/v1/setup", { body: { ...setupTokenBody(), ...device.keys } }), 429, "rate_limited");
    // The reset route has its own counter.
    await call("POST", "/v1/reset", { body: { ...setupTokenBody(), confirm: "erase everything" } });
    await setupFirstDevice();
  });

  it("unlocks after the lockout hour", async () => {
    const device = new TestDevice();
    for (let i = 0; i < 10; i++) {
      await expectError(api("POST", "/v1/setup", { body: { setup_token: "w".repeat(40), ...device.keys } }), 403, "forbidden");
    }
    await expectError(api("POST", "/v1/setup", { body: { ...setupTokenBody(), ...device.keys } }), 429, "rate_limited");
    await hubSql("UPDATE lockouts SET locked_until = 1");
    await setupFirstDevice();
  });
});

describe("POST /v1/reset", () => {
  it("wipes everything and allows setup again", async () => {
    const device = await setupFirstDevice();
    await call("POST", "/v1/pair/offer", { token: device.token, body: {} });
    await expectError(api("POST", "/v1/reset", { body: { ...setupTokenBody(), confirm: "yes" } }), 400, "bad_request");
    await call("POST", "/v1/reset", { body: { ...setupTokenBody(), confirm: "erase everything" } });
    expect((await call("GET", "/v1/info")).setup_done).toBe(false);
    await expectError(api("GET", "/v1/devices", { token: device.token }), 401, "unauthorized");
    expect(await hubSql("SELECT * FROM pairings")).toEqual([]);
    await setupFirstDevice();
  });

  it("locks after 10 wrong setup tokens", async () => {
    const body = { setup_token: "w".repeat(40), confirm: "erase everything" };
    for (let i = 0; i < 10; i++) await expectError(api("POST", "/v1/reset", { body }), 403, "forbidden");
    await expectError(api("POST", "/v1/reset", { body: { ...setupTokenBody(), confirm: "erase everything" } }), 429, "rate_limited");
  });
});
