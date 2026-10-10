// Device bearer tokens `ct1.<device_id>.<b64 rand32>.<b64 mac16>` (SPEC §6).

import { b64decodeLength, b64encode } from './b64.ts';
import { concat, equalBytes, lp, utf8, type Bytes } from './bytes.ts';
import { isDeviceId } from './device.ts';
import { defaultRng, hmacSha256, sha256, type Rng } from './primitives.ts';

const PREFIX = 'ct1';

export interface ParsedToken {
  deviceId: string;
  rand: Bytes;
  mac: Bytes;
}

/** `mac16 = HMAC-SHA256(token_key, lp(device_id) || lp(rand32))[0..16]` */
export function tokenMac(tokenKey: Bytes, deviceId: string, rand: Bytes): Bytes {
  return hmacSha256(tokenKey, concat(lp(deviceId), lp(rand))).slice(0, 16);
}

export function makeToken(tokenKey: Bytes, deviceId: string, options: { rand?: Bytes; rng?: Rng } = {}): string {
  if (!isDeviceId(deviceId)) throw new RangeError('invalid device id');
  const rand = options.rand ?? (options.rng ?? defaultRng)(32);
  if (rand.length !== 32) throw new RangeError('rand must be 32 bytes');
  return [PREFIX, deviceId, b64encode(rand), b64encode(tokenMac(tokenKey, deviceId, rand))].join('.');
}

/** Strict syntactic parse; null when malformed. Does not check the MAC. */
export function parseToken(token: unknown): ParsedToken | null {
  if (typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 4) return null;
  const [prefix, deviceId, randB64, macB64] = parts as [string, string, string, string];
  if (prefix !== PREFIX || !isDeviceId(deviceId)) return null;
  const rand = b64decodeLength(randB64, 32);
  const mac = b64decodeLength(macB64, 16);
  return rand && mac ? { deviceId, rand, mac } : null;
}

/** Parses and checks the MAC in constant time. Returns the device id, or null. */
export function verifyToken(token: unknown, tokenKey: Bytes): string | null {
  const parsed = parseToken(token);
  if (parsed === null) return null;
  return equalBytes(parsed.mac, tokenMac(tokenKey, parsed.deviceId, parsed.rand)) ? parsed.deviceId : null;
}

/** `SHA-256(token)` over the token's UTF-8 bytes, as stored by the Hub. */
export function tokenHash(token: string): Bytes {
  return sha256(utf8(token));
}
