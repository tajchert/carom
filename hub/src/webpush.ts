// Web Push with WebCrypto only: RFC 8291 (aes128gcm message encryption) and RFC 8292 (VAPID).

import { hkdf, randomBytes } from "./crypto";
import { b64decode, b64encode, concat, utf8 } from "./encoding";

const RECORD_SIZE = 4096;
const P256_POINT_BYTES = 65;

export interface PushSubscription {
  endpoint: string;
  /** Uncompressed P-256 point of the user agent (65 bytes). */
  p256dh: Uint8Array;
  /** 16-byte authentication secret. */
  auth: Uint8Array;
}

/** An ECDH P-256 key pair plus its uncompressed public point. */
export interface EcdhKeyPair {
  privateKey: CryptoKey;
  publicRaw: Uint8Array;
}

export async function generateEcdhKeyPair(): Promise<EcdhKeyPair> {
  const pair = (await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"])) as CryptoKeyPair;
  const publicRaw = new Uint8Array((await crypto.subtle.exportKey("raw", pair.publicKey)) as ArrayBuffer);
  return { privateKey: pair.privateKey, publicRaw };
}

/** The JWK coordinates of an uncompressed P-256 point. */
export function pointToJwk(point: Uint8Array): { x: string; y: string } {
  if (point.length !== P256_POINT_BYTES || point[0] !== 0x04) throw new Error("not an uncompressed P-256 point");
  return { x: b64encode(point.slice(1, 33)), y: b64encode(point.slice(33, 65)) };
}

export interface EncryptOptions {
  /** Injected for deterministic tests (RFC 8291 Appendix A); random by default. */
  serverKeys?: EcdhKeyPair;
  salt?: Uint8Array;
}

/** RFC 8291 §3-4: a single aes128gcm record carrying `plaintext`. */
export async function encryptPayload(sub: Pick<PushSubscription, "p256dh" | "auth">, plaintext: Uint8Array, opts: EncryptOptions = {}): Promise<Uint8Array> {
  const server = opts.serverKeys ?? (await generateEcdhKeyPair());
  const salt = opts.salt ?? randomBytes(16);
  const uaPublic = await crypto.subtle.importKey("raw", sub.p256dh, { name: "ECDH", namedCurve: "P-256" }, false, []);
  // Held in a variable because workers-types misnames the standard `public` member as `$public`.
  const ecdh = { name: "ECDH", public: uaPublic };
  const ecdhSecret = new Uint8Array(await crypto.subtle.deriveBits(ecdh, server.privateKey, 256));

  const keyInfo = concat(utf8("WebPush: info\0"), sub.p256dh, server.publicRaw);
  const ikm = await hkdf(ecdhSecret, sub.auth, keyInfo, 32);
  const cek = await hkdf(ikm, salt, "Content-Encoding: aes128gcm\0", 16);
  const nonce = await hkdf(ikm, salt, "Content-Encoding: nonce\0", 12);

  // A single, final record: plaintext followed by the 0x02 padding delimiter.
  const record = concat(plaintext, new Uint8Array([0x02]));
  if (record.length + 16 > RECORD_SIZE) throw new Error("push payload too large for one record");
  const key = await crypto.subtle.importKey("raw", cek, "AES-GCM", false, ["encrypt"]);
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce }, key, record));

  const header = new Uint8Array(16 + 4 + 1);
  header.set(salt, 0);
  new DataView(header.buffer).setUint32(16, RECORD_SIZE);
  header[20] = server.publicRaw.length;
  return concat(header, server.publicRaw, ciphertext);
}

export interface VapidKeys {
  /** ECDSA P-256 signing key. */
  privateKey: CryptoKey;
  /** b64 uncompressed public point, as sent in the `k=` parameter and in /v1/info. */
  publicKey: string;
}

export interface StoredVapidKeys {
  privateJwk: JsonWebKey;
  publicKey: string;
}

export async function generateVapidKeys(): Promise<StoredVapidKeys> {
  const pair = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"])) as CryptoKeyPair;
  const privateJwk = (await crypto.subtle.exportKey("jwk", pair.privateKey)) as JsonWebKey;
  const publicRaw = new Uint8Array((await crypto.subtle.exportKey("raw", pair.publicKey)) as ArrayBuffer);
  return { privateJwk, publicKey: b64encode(publicRaw) };
}

export async function importVapidKeys(stored: StoredVapidKeys): Promise<VapidKeys> {
  const privateKey = await crypto.subtle.importKey("jwk", stored.privateJwk, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  return { privateKey, publicKey: stored.publicKey };
}

export const VAPID_JWT_LIFETIME_S = 12 * 60 * 60;

/** RFC 8292 §2: an ES256 JWT with aud = push service origin. */
export async function createVapidJwt(privateKey: CryptoKey, aud: string, sub: string, expS: number): Promise<string> {
  const encodeJson = (value: unknown) => b64encode(utf8(JSON.stringify(value)));
  const signingInput = `${encodeJson({ typ: "JWT", alg: "ES256" })}.${encodeJson({ aud, exp: expS, sub })}`;
  // WebCrypto ECDSA emits the raw r||s form that JWS ES256 expects.
  const signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, privateKey, utf8(signingInput));
  return `${signingInput}.${b64encode(new Uint8Array(signature))}`;
}

export function vapidAuthorization(jwt: string, publicKey: string): string {
  return `vapid t=${jwt}, k=${publicKey}`;
}

/** Builds the RFC 8030 push request for an encrypted body. */
export function pushRequest(endpoint: string, body: Uint8Array, authorization: string): Request {
  return new Request(endpoint, {
    method: "POST",
    headers: {
      Authorization: authorization,
      "Content-Encoding": "aes128gcm",
      "Content-Type": "application/octet-stream",
      TTL: "86400",
      Urgency: "high",
    },
    body,
  });
}

/** Parses the b64 subscription keys sent by a device. */
export function decodeSubscriptionKeys(p256dh: string, auth: string): { p256dh: Uint8Array; auth: Uint8Array } | null {
  const point = b64decode(p256dh);
  const secret = b64decode(auth);
  if (point?.length !== P256_POINT_BYTES || point[0] !== 0x04 || secret?.length !== 16) return null;
  return { p256dh: point, auth: secret };
}
