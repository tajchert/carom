import { beforeEach, describe, expect, it } from "vitest";
import pkg from "../package.json";
import { runInDurableObject } from "cloudflare:test";
import type { Hub } from "../src/hub";
import { setupFingerprint } from "../src/token";
import { TEST_SETUP_TOKEN } from "./constants";
import { api, call, expectError, hubSql, hubStub, setupFirstDevice, setupTokenBody, TestDevice, wipeHub } from "./helpers";

beforeEach(wipeHub);

describe("GET /v1/info", () => {
  it("describes the hub without auth", async () => {
    const info = await call("GET", "/v1/info");
    expect(info).toMatchObject({ name: "carom-hub", protocol: 1, version: pkg.version, setup_done: false, jurisdiction: null });
    expect(Object.keys(info).sort()).toEqual(["jurisdiction", "name", "protocol", "setup_done", "version"]);
  });

  it("reports setup_done once the first device registered", async () => {
    await setupFirstDevice();
    expect((await call("GET", "/v1/info")).setup_done).toBe(true);
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

  it("still accepts the right token after many wrong ones", async () => {
    const device = new TestDevice();
    const wrong = { setup_token: "w".repeat(40), ...device.keys };
    for (let i = 0; i < 20; i++) await expectError(api("POST", "/v1/setup", { body: wrong }), 403, "forbidden");
    await setupFirstDevice();
  });
});

describe("changing SETUP_TOKEN", () => {
  const OTHER_TOKEN = "another-setup-token-that-is-long-enough-0123";

  /** Runs `fn` while the Hub object sees `token` as its SETUP_TOKEN. */
  async function withHubToken<T>(token: string, fn: () => Promise<T>): Promise<T> {
    const swap = (value: string) =>
      runInDurableObject(hubStub(), (hub: Hub) => {
        const hubEnv: object = Reflect.get(hub, "env");
        const previous: string = Reflect.get(hubEnv, "SETUP_TOKEN");
        Reflect.set(hubEnv, "SETUP_TOKEN", value);
        return previous;
      });
    const previous = await swap(token);
    try {
      return await fn();
    } finally {
      await swap(previous);
    }
  }

  const fingerprintRows = () => hubSql<{ value: string }>("SELECT value FROM meta WHERE key = 'setup_fingerprint'");

  it("setup stores a fingerprint of the token, not the token", async () => {
    await setupFirstDevice();
    const [row] = await fingerprintRows();
    expect(row!.value).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(row!.value).not.toContain(TEST_SETUP_TOKEN);
    expect(row!.value).toBe(await setupFingerprint(TEST_SETUP_TOKEN));
  });

  it("wipes the Hub on the next request once the token differs, and allows setup again", async () => {
    const device = await setupFirstDevice();
    await call("POST", "/v1/pair/offer", { token: device.token, body: {} });
    await withHubToken(OTHER_TOKEN, async () => {
      expect((await call("GET", "/v1/info")).setup_done).toBe(false);
    });
    expect(await hubSql("SELECT * FROM pairings")).toEqual([]);
    expect(await hubSql("SELECT * FROM devices")).toEqual([]);
    expect(await fingerprintRows()).toEqual([]);
    await expectError(api("GET", "/v1/devices", { token: device.token }), 401, "unauthorized");
    await setupFirstDevice();
  });

  it("a Hub claimed before fingerprints existed gets one and keeps its data", async () => {
    const device = await setupFirstDevice();
    await hubSql("DELETE FROM meta WHERE key = 'setup_fingerprint'");
    expect((await call("GET", "/v1/devices", { token: device.token })).devices).toHaveLength(1);
    expect(await fingerprintRows()).toHaveLength(1);
  });

  it("does not touch an unclaimed Hub", async () => {
    await withHubToken(OTHER_TOKEN, async () => {
      expect((await call("GET", "/v1/info")).setup_done).toBe(false);
    });
    expect(await fingerprintRows()).toEqual([]);
  });
});
