import { env } from "cloudflare:workers";
import { beforeEach, describe, expect, it } from "vitest";
import worker from "../src/index";
import { MESSAGE_BODY_LIMIT } from "../src/routes";
import { TEST_SETUP_TOKEN } from "./constants.ts";
import { api, b64, BASE, call, expectError, setupFirstDevice, TestDevice, wipeHub } from "./helpers";

beforeEach(wipeHub);

/** A HUB binding that records use, to prove the Durable Object was never contacted. */
function trackingNamespace() {
  const used: string[] = [];
  const namespace = {
    idFromName: () => {
      used.push("idFromName");
      throw new Error("Durable Object contacted");
    },
    get: () => {
      used.push("get");
      throw new Error("Durable Object contacted");
    },
    jurisdiction: (j: string) => {
      used.push(`jurisdiction:${j}`);
      return namespace;
    },
  };
  return { used, namespace: namespace as unknown as Env["HUB"] };
}

function workerFetch(request: Request, overrides: Record<string, unknown>): Promise<Response> {
  const testEnv = { ...env, SETUP_TOKEN: TEST_SETUP_TOKEN, ...overrides } as Env;
  // Requests built in tests carry no incoming `cf` properties.
  return worker.fetch(request as Request<unknown, IncomingRequestCfProperties>, testEnv);
}

function forgedToken(deviceId: string): string {
  return `ct1.${deviceId}.${b64(new Uint8Array(32))}.${b64(new Uint8Array(16))}`;
}

describe("token verification in the Worker", () => {
  it("rejects bad tokens before touching the Durable Object", async () => {
    const { used, namespace } = trackingNamespace();
    const device = new TestDevice();
    for (const auth of [undefined, "Bearer nonsense", `Bearer ${forgedToken(device.id)}`, "Basic abc"]) {
      const headers: Record<string, string> = auth ? { Authorization: auth } : {};
      const res = await workerFetch(new Request(`${BASE}/v1/devices`, { headers }), { HUB: namespace });
      await expectError(res, 401, "unauthorized");
    }
    expect(used).toEqual([]);
  });

  it("rejects tokens minted under another SETUP_TOKEN", async () => {
    const device = await setupFirstDevice();
    const res = await workerFetch(new Request(`${BASE}/v1/devices`, { headers: { Authorization: `Bearer ${device.token}` } }), {
      SETUP_TOKEN: "another-setup-token-that-is-long-enough-0123",
    });
    await expectError(res, 401, "unauthorized");
  });

  it("accepts a valid token", async () => {
    const device = await setupFirstDevice();
    await call("GET", "/v1/devices", { token: device.token });
  });

  it("strips client-sent x-carom-* headers", async () => {
    const a = await setupFirstDevice();
    const other = new TestDevice();
    // Without a token, injected identity headers are not trusted.
    await expectError(api("GET", "/v1/devices", { headers: { "x-carom-device": a.id, "x-carom-token-hash": "x" } }), 401, "unauthorized");
    // With A's token, an injected device header is replaced by A's id.
    await call("PUT", "/v1/devices/self/record", { token: a.token, headers: { "x-carom-device": other.id }, body: { record: a.record() } });
  });
});

describe("configuration fails closed", () => {
  it("refuses everything but /v1/info without a usable SETUP_TOKEN", async () => {
    const device = new TestDevice();
    for (const token of [undefined, "short-token"]) {
      const setup = new Request(`${BASE}/v1/setup`, {
        method: "POST",
        body: JSON.stringify({ setup_token: token ?? "", ...device.keys }),
      });
      await expectError(workerFetch(setup, { SETUP_TOKEN: token }), 503, "unavailable");
      const authed = new Request(`${BASE}/v1/devices`, { headers: { Authorization: `Bearer ${forgedToken(device.id)}` } });
      await expectError(workerFetch(authed, { SETUP_TOKEN: token }), 503, "unavailable");
      const info = await workerFetch(new Request(`${BASE}/v1/info`), { SETUP_TOKEN: token });
      expect(info.status).toBe(200);
    }
  });

  it("rejects an unknown jurisdiction and uses env.HUB.jurisdiction for eu", async () => {
    await expectError(workerFetch(new Request(`${BASE}/v1/info`), { HUB_JURISDICTION: "mars" }), 503, "unavailable");
    const { used, namespace } = trackingNamespace();
    const res = await workerFetch(new Request(`${BASE}/v1/info`), { HUB_JURISDICTION: "eu", HUB: namespace });
    expect(res.status).toBe(500);
    expect(used.slice(0, 2)).toEqual(["jurisdiction:eu", "idFromName"]);
  });
});

describe("routing and limits", () => {
  it("answers unknown routes and methods with not_found", async () => {
    await expectError(api("GET", "/"), 404, "not_found");
    await expectError(api("GET", "/v1/nope"), 404, "not_found");
    await expectError(api("PATCH", "/v1/info"), 404, "not_found");
    await expectError(api("GET", "/v1/pair/bad.id"), 404, "not_found");
  });

  it("rejects bodies over the route limit, declared or streamed", async () => {
    const device = await setupFirstDevice();
    const big = "x".repeat(MESSAGE_BODY_LIMIT + 1);
    await expectError(api("POST", "/v1/messages", { token: device.token, rawBody: big }), 413, "too_large");
    const stream = new ReadableStream({
      start(controller) {
        for (let i = 0; i < 8; i++) controller.enqueue(new TextEncoder().encode("y".repeat(1024)));
        controller.close();
      },
    });
    // POST /v1/acks allows 4 KiB; the streamed body carries no Content-Length.
    await expectError(api("POST", "/v1/acks", { token: device.token, rawBody: stream }), 413, "too_large");
  });

  it("requires an Upgrade header on /v1/ws", async () => {
    const device = await setupFirstDevice();
    await expectError(api("GET", "/v1/ws", { token: device.token }), 400, "bad_request");
  });
});

/** A rate limiter that refuses `deny` keys and records every key it was asked about. */
function stubLimiter(deny: (key: string) => boolean = () => false) {
  const keys: string[] = [];
  const limiter = {
    limit: async ({ key }: { key: string }) => {
      keys.push(key);
      return { success: !deny(key) };
    },
  };
  return { keys, limiter: limiter as unknown as RateLimit };
}

describe("rate limits and the setup token in the Worker", () => {
  it("refuses a wrong setup token without contacting the Durable Object", async () => {
    const { used, namespace } = trackingNamespace();
    const device = new TestDevice();
    const path = "/v1/setup";
    const res = await workerFetch(
      new Request(BASE + path, { method: "POST", body: JSON.stringify({ setup_token: "w".repeat(40), ...device.keys }) }),
      { HUB: namespace },
    );
    await expectError(Promise.resolve(res), 403, "forbidden");
    const garbage = await workerFetch(new Request(BASE + path, { method: "POST", body: "not json" }), { HUB: namespace });
    await expectError(Promise.resolve(garbage), 400, "bad_request");
    expect(used).toEqual([]);
  });

  it("limits unauthenticated routes per route and client IP", async () => {
    const { used, namespace } = trackingNamespace();
    const anon = stubLimiter(() => true);
    const request = new Request(BASE + "/v1/pair/accept", { method: "POST", headers: { "CF-Connecting-IP": "203.0.113.7" }, body: "{}" });
    await expectError(workerFetch(request, { HUB: namespace, ANON_LIMITER: anon.limiter }), 429, "rate_limited");
    expect(anon.keys).toEqual(["pair.accept:203.0.113.7"]);
    expect(used).toEqual([]);
  });

  it("limits authenticated routes per device, after the token MAC check", async () => {
    const device = await setupFirstDevice();
    const anon = stubLimiter();
    const perDevice = stubLimiter((key) => key === device.id);
    const { used, namespace } = trackingNamespace();
    const authed = () => new Request(BASE + "/v1/devices", { headers: { Authorization: `Bearer ${device.token}` } });
    await expectError(workerFetch(authed(), { HUB: namespace, ANON_LIMITER: anon.limiter, DEVICE_LIMITER: perDevice.limiter }), 429, "rate_limited");
    expect(used).toEqual([]);
    // A forged token is refused before it can spend anyone's budget.
    const forged = new Request(BASE + "/v1/devices", { headers: { Authorization: `Bearer ${forgedToken(device.id)}` } });
    await expectError(workerFetch(forged, { HUB: namespace, DEVICE_LIMITER: perDevice.limiter }), 401, "unauthorized");
    expect(perDevice.keys).toEqual([device.id]);
    expect(anon.keys).toEqual([]);
  });
});

describe("Date header", () => {
  const IMF_FIXDATE = /^[A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} GMT$/;

  function expectHubClock(res: Response): void {
    const date = res.headers.get("Date") ?? "";
    expect(date).toMatch(IMF_FIXDATE);
    expect(Math.abs(Date.parse(date) - Date.now())).toBeLessThan(5000);
  }

  it("is on success and error responses", async () => {
    const device = await setupFirstDevice();
    expectHubClock(await api("GET", "/v1/info"));
    expectHubClock(await api("GET", "/v1/devices", { token: device.token }));
    expectHubClock(await api("GET", "/v1/nothing-here"));
    expectHubClock(await api("GET", "/v1/devices"));
    expectHubClock(await api("POST", "/v1/setup", { body: { setup_token: "w".repeat(40), ...device.keys } }));
  });

  it("is on responses the Worker produces without the Hub", async () => {
    const res = await workerFetch(new Request(`${BASE}/v1/devices`), { SETUP_TOKEN: undefined });
    expect(res.status).toBe(503);
    expectHubClock(res);
  });

  it("is not added to the WebSocket upgrade", async () => {
    const device = await setupFirstDevice();
    const res = await api("GET", "/v1/ws", { token: device.token, headers: { Upgrade: "websocket" } });
    expect(res.status).toBe(101);
    res.webSocket!.accept();
    res.webSocket!.close();
  });
});
