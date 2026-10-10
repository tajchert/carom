import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
import { BASE, b64, call, connect, hubSql, pairDevice, send, setupFirstDevice, unb64, wipeHub, type TestDevice } from "./helpers";

const ENDPOINT = "https://push.example.com/send/abc123";

interface CapturedPush {
  url: string;
  method: string;
  headers: Headers;
  body: Uint8Array;
}

let fetchSpy: MockInstance<typeof fetch>;
let pushes: CapturedPush[];
let pushStatus = 201;

beforeEach(async () => {
  await wipeHub();
  pushes = [];
  pushStatus = 201;
  // The body is read inside the Hub's I/O context; tests only see the captured copy.
  fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const request = input as Request;
    const body = new Uint8Array(await request.arrayBuffer());
    pushes.push({ url: request.url, method: request.method, headers: request.headers, body });
    return new Response(null, { status: pushStatus });
  });
});

afterEach(() => {
  fetchSpy.mockRestore();
});

interface UserAgent {
  keys: CryptoKeyPair;
  publicRaw: Uint8Array;
  auth: Uint8Array;
}

async function subscribe(device: TestDevice): Promise<UserAgent> {
  const keys = (await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"])) as CryptoKeyPair;
  const publicRaw = new Uint8Array((await crypto.subtle.exportKey("raw", keys.publicKey)) as ArrayBuffer);
  const auth = crypto.getRandomValues(new Uint8Array(16));
  await call("PUT", "/v1/devices/self/push", { token: device.token, body: { endpoint: ENDPOINT, p256dh: b64(publicRaw), auth: b64(auth) } });
  return { keys, publicRaw, auth };
}

async function hkdf(ikm: Uint8Array, salt: Uint8Array, info: Uint8Array, bytes: number): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey("raw", ikm, "HKDF", false, ["deriveBits"]);
  return new Uint8Array(await crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt, info }, key, bytes * 8));
}

/** The receiving side of RFC 8291, written out independently of src/webpush.ts. */
async function decrypt(ua: UserAgent, body: Uint8Array): Promise<string> {
  const salt = body.slice(0, 16);
  const idlen = body[20]!;
  const asPublic = body.slice(21, 21 + idlen);
  const ciphertext = body.slice(21 + idlen);
  const serverKey = await crypto.subtle.importKey("raw", asPublic, { name: "ECDH", namedCurve: "P-256" }, false, []);
  const ecdh = { name: "ECDH", public: serverKey };
  const secret = new Uint8Array(await crypto.subtle.deriveBits(ecdh, ua.keys.privateKey, 256));
  const te = new TextEncoder();
  const keyInfo = new Uint8Array([...te.encode("WebPush: info\0"), ...ua.publicRaw, ...asPublic]);
  const ikm = await hkdf(secret, ua.auth, keyInfo, 32);
  const cek = await hkdf(ikm, salt, te.encode("Content-Encoding: aes128gcm\0"), 16);
  const nonce = await hkdf(ikm, salt, te.encode("Content-Encoding: nonce\0"), 12);
  const key = await crypto.subtle.importKey("raw", cek, "AES-GCM", false, ["decrypt"]);
  const plain = new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: nonce }, key, ciphertext));
  expect(plain[plain.length - 1]).toBe(2);
  return new TextDecoder().decode(plain.slice(0, -1));
}

describe("Web Push wake-up", () => {
  it("pushes an encrypted, VAPID-signed wake to an offline subscriber", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    const ua = await subscribe(b);
    await send(a, { to: [b.id] });
    await vi.waitFor(() => expect(fetchSpy).toHaveBeenCalledTimes(1));

    await vi.waitFor(() => expect(pushes).toHaveLength(1));
    const request = pushes[0]!;
    expect(request.url).toBe(ENDPOINT);
    expect(request.method).toBe("POST");
    expect(request.headers.get("TTL")).toBe("86400");
    expect(request.headers.get("Urgency")).toBe("high");
    expect(request.headers.get("Content-Encoding")).toBe("aes128gcm");
    expect(await decrypt(ua, request.body)).toBe('{"t":"wake"}');

    const { vapid_public_key } = await call("GET", "/v1/info");
    const match = /^vapid t=([^,]+), k=(.+)$/.exec(request.headers.get("Authorization")!)!;
    expect(match[2]).toBe(vapid_public_key);
    const [h, p, s] = match[1]!.split(".") as [string, string, string];
    const claims = JSON.parse(new TextDecoder().decode(unb64(p)));
    expect(claims.aud).toBe("https://push.example.com");
    expect(claims.sub).toBe(new URL(BASE).origin);
    expect(claims.exp).toBeGreaterThan(Date.now() / 1000 + 11 * 3600);
    expect(claims.exp).toBeLessThanOrEqual(Date.now() / 1000 + 12 * 3600 + 1);
    const vapidKey = await crypto.subtle.importKey("raw", unb64(vapid_public_key), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    expect(await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, vapidKey, unb64(s), new TextEncoder().encode(`${h}.${p}`))).toBe(true);
  });

  it("sends at most one push until the device fetches, or after 5 minutes", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    await subscribe(b);
    await send(a, { to: [b.id] });
    await send(a, { to: [b.id] });
    await vi.waitFor(() => expect(fetchSpy).toHaveBeenCalledTimes(1));

    await call("GET", "/v1/messages", { token: b.token });
    await send(a, { to: [b.id] });
    await vi.waitFor(() => expect(fetchSpy).toHaveBeenCalledTimes(2));

    await hubSql("UPDATE devices SET push_sent_at = ? WHERE id = ?", Date.now() - 5 * 60 * 1000 - 1, b.id);
    await send(a, { to: [b.id] });
    await vi.waitFor(() => expect(fetchSpy).toHaveBeenCalledTimes(3));
  });

  it("skips devices with a live socket or without a subscription", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    const c = await pairDevice(a);
    await subscribe(b);
    const socket = await connect(b);
    await send(a, { to: [b.id, c.id] });
    await socket.next();
    socket.ws.close();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("drops the subscription when the push service answers 410", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    await subscribe(b);
    pushStatus = 410;
    await send(a, { to: [b.id] });
    await vi.waitFor(async () => {
      const [row] = await hubSql<{ push_endpoint: string | null }>("SELECT push_endpoint FROM devices WHERE id = ?", b.id);
      expect(row!.push_endpoint).toBeNull();
    });
    const { devices } = await call("GET", "/v1/devices", { token: a.token });
    expect(devices.find((d: { id: string }) => d.id === b.id).has_push).toBe(false);
  });
});
