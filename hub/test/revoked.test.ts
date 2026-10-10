// A handler that awaits after authentication (reading the body, verifying a signature) must
// re-check that its caller is still active right before it writes (SPEC §5.4, §5.5).

import { beforeEach, describe, expect, it } from "vitest";
import { b64, call, expectError, FAKE_BOX, hubSql, heldRequest, pairDevice, setupFirstDevice, wipeHub } from "./helpers";

beforeEach(wipeHub);

const P256DH = b64(Uint8Array.from({ length: 65 }, (_, i) => (i === 0 ? 4 : i)));
const AUTH = b64(new Uint8Array(16).fill(5));

describe("a device revoked while its request body is still arriving", () => {
  it("cannot publish the next keyring version", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    const held = await heldRequest(b, "PUT", "/v1/keyring", { kv: 2, by: b.id, boxes: { [b.id]: FAKE_BOX }, sig: b64(new Uint8Array(64)) });
    await call("DELETE", `/v1/devices/${b.id}`, { token: a.token });
    await expectError(held.release(), 401, "unauthorized");
    expect(await hubSql("SELECT kv FROM keyring")).toEqual([]);
    // The revoker's mandatory rekey is not blocked.
    await call("PUT", "/v1/keyring", { token: a.token, body: { kv: 2, by: a.id, boxes: { [a.id]: FAKE_BOX }, sig: b64(new Uint8Array(64)) } });
  });

  it("cannot publish a record, a push subscription or an ack", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    const requests = [
      await heldRequest(b, "PUT", "/v1/devices/self/record", { record: b.record() }),
      await heldRequest(b, "PUT", "/v1/devices/self/push", { endpoint: "https://push.example.com/x", p256dh: P256DH, auth: AUTH }),
      await heldRequest(b, "POST", "/v1/acks", { up_to: 0 }),
    ];
    await call("DELETE", `/v1/devices/${b.id}`, { token: a.token });
    for (const held of requests) await expectError(held.release(), 401, "unauthorized");
  });
});
