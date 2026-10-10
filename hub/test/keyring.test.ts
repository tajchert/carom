import { beforeEach, describe, expect, it } from "vitest";
import { api, b64, call, connect, expectError, pairDevice, setupFirstDevice, wipeHub, type Json } from "./helpers";

beforeEach(wipeHub);

const box = () => ({ epk: b64(crypto.getRandomValues(new Uint8Array(32))), ct: b64(crypto.getRandomValues(new Uint8Array(48))) });
const sig = () => b64(crypto.getRandomValues(new Uint8Array(64)));

describe("keyring", () => {
  it("serializes versions: kv must be current + 1", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    const socket = await connect(b);
    const v2 = { kv: 2, by: a.id, boxes: { [a.id]: box(), [b.id]: box() }, sig: sig() };
    await call("PUT", "/v1/keyring", { token: a.token, body: v2 });
    expect(await socket.next()).toEqual({ t: "dir" });
    // A concurrent rekey to the same kv loses.
    await expectError(api("PUT", "/v1/keyring", { token: b.token, body: { ...v2, by: b.id } }), 409, "conflict");
    await expectError(api("PUT", "/v1/keyring", { token: b.token, body: { ...v2, by: b.id, kv: 4 } }), 409, "conflict");
    const v3 = { kv: 3, by: b.id, boxes: { [a.id]: box(), [b.id]: box() }, sig: sig() };
    await call("PUT", "/v1/keyring", { token: b.token, body: v3 });

    expect(await call("GET", "/v1/keyring", { token: a.token })).toEqual({ versions: [v2, v3] });
    expect(await call("GET", "/v1/keyring?have=2", { token: a.token })).toEqual({ versions: [v3] });
    expect(await call("GET", "/v1/keyring?have=3", { token: a.token })).toEqual({ versions: [] });
    socket.ws.close();
  });

  it("accepts the setup key kv 1 on an empty keyring", async () => {
    const a = await setupFirstDevice();
    await call("PUT", "/v1/keyring", { token: a.token, body: { kv: 1, by: a.id, boxes: { [a.id]: box() }, sig: sig() } });
    await expectError(api("PUT", "/v1/keyring", { token: a.token, body: { kv: 1, by: a.id, boxes: { [a.id]: box() }, sig: sig() } }), 409, "conflict");
    await call("PUT", "/v1/keyring", { token: a.token, body: { kv: 2, by: a.id, boxes: { [a.id]: box() }, sig: sig() } });
  });

  it("validates the request", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    const good: Json = { kv: 2, by: a.id, boxes: { [a.id]: box() }, sig: sig() };
    await expectError(api("PUT", "/v1/keyring", { token: a.token, body: { ...good, by: b.id } }), 403, "forbidden");
    for (const bad of [
      { ...good, kv: 0 },
      { ...good, boxes: {} },
      { ...good, boxes: { nope: box() } },
      { ...good, boxes: { [a.id]: { epk: "AA", ct: box().ct } } },
      { ...good, sig: "AA" },
      { ...good, extra: 1 },
    ]) {
      await expectError(api("PUT", "/v1/keyring", { token: a.token, body: bad }), 400, "bad_request");
    }
    await expectError(api("GET", "/v1/keyring?have=-1", { token: a.token }), 400, "bad_request");
  });
});
