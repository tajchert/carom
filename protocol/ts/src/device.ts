// Device identity and device ids (SPEC §2.1, §2.2).

import { b64decodeLength, b64encode } from './b64.ts';
import type { Bytes } from './bytes.ts';
import { defaultRng, ed25519PublicKey, sha256, x25519PublicKey, type Rng } from './primitives.ts';

export interface Identity {
  deviceId: string;
  /** Ed25519 32-byte seed (RFC 8032 private key). */
  signSk: Bytes;
  signPub: Bytes;
  /** X25519 32-byte scalar (clamped at use, RFC 7748). */
  kxSk: Bytes;
  kxPub: Bytes;
}

const DEVICE_ID_RE = /^dev_[A-Za-z0-9_-]{22}$/;

/** `device_id = "dev_" || b64(SHA-256(sign_pub)[0..16])` */
export function deviceIdFromSignPub(signPub: Bytes): string {
  if (signPub.length !== 32) throw new RangeError('sign_pub must be 32 bytes');
  return 'dev_' + b64encode(sha256(signPub).subarray(0, 16));
}

/** Syntactic check: "dev_" plus the canonical b64 of 16 bytes. */
export function isDeviceId(s: unknown): s is string {
  return typeof s === 'string' && DEVICE_ID_RE.test(s) && b64decodeLength(s.slice(4), 16) !== null;
}

/** True when `id` is exactly the id derived from `signPub`. */
export function deviceIdMatches(id: string, signPub: Bytes): boolean {
  return signPub.length === 32 && deviceIdFromSignPub(signPub) === id;
}

export function identityFromSecrets(signSk: Bytes, kxSk: Bytes): Identity {
  if (signSk.length !== 32 || kxSk.length !== 32) throw new RangeError('secret keys must be 32 bytes');
  const signPub = ed25519PublicKey(signSk);
  return { deviceId: deviceIdFromSignPub(signPub), signSk, signPub, kxSk, kxPub: x25519PublicKey(kxSk) };
}

export function generateIdentity(rng: Rng = defaultRng): Identity {
  return identityFromSecrets(rng(32), rng(32));
}

/** Device ids in ascending byte order (ids are ASCII, so code-unit order is byte order). */
export function sortIds(ids: Iterable<string>): string[] {
  return [...ids].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
}
