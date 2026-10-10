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
