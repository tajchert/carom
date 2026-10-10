// Device tokens (SPEC §6): ct1.<device_id>.<b64 rand32>.<b64 mac16>, MAC-keyed by SETUP_TOKEN.

import { constantTimeEqual, hkdf, hmacSha256, randomBytes, sha256 } from "./crypto";
import { b64decode, b64encode, concat, lp, utf8 } from "./encoding";
import { DEVICE_ID } from "./ids";

export const MIN_SETUP_TOKEN_LENGTH = 32;

export function isUsableSetupToken(secret: string | undefined): secret is string {
  return typeof secret === "string" && secret.length >= MIN_SETUP_TOKEN_LENGTH;
}

async function tokenKey(setupToken: string): Promise<Uint8Array> {
  return hkdf(utf8(setupToken), new Uint8Array(0), "carom/v1/token-key", 32);
}

async function tokenMac(setupToken: string, deviceId: string, rand: Uint8Array): Promise<Uint8Array> {
  const mac = await hmacSha256(await tokenKey(setupToken), concat(lp(deviceId), lp(rand)));
  return mac.slice(0, 16);
}

export async function mintToken(setupToken: string, deviceId: string): Promise<string> {
  const rand = randomBytes(32);
  const mac = await tokenMac(setupToken, deviceId, rand);
  return `ct1.${deviceId}.${b64encode(rand)}.${b64encode(mac)}`;
}

/** Returns the device id when the token is well formed and its MAC verifies, otherwise null. */
export async function verifyTokenMac(setupToken: string, token: string): Promise<string | null> {
  const parts = token.split(".");
  if (parts.length !== 4 || parts[0] !== "ct1") return null;
  const [, deviceId, randText, macText] = parts as [string, string, string, string];
  if (!DEVICE_ID.test(deviceId)) return null;
  const rand = b64decode(randText);
  const mac = b64decode(macText);
  if (rand?.length !== 32 || mac?.length !== 16) return null;
  const expected = await tokenMac(setupToken, deviceId, rand);
  return constantTimeEqual(expected, mac) ? deviceId : null;
}

/** What the Hub stores per device: b64(SHA-256(token)). */
export async function tokenHash(token: string): Promise<string> {
  return b64encode(await sha256(utf8(token)));
}

/** Constant-time comparison of a presented setup token against the secret, via their SHA-256. */
export async function setupTokenMatches(secret: string, presented: string): Promise<boolean> {
  const [a, b] = await Promise.all([sha256(utf8(secret)), sha256(utf8(presented))]);
  return constantTimeEqual(a, b);
}
