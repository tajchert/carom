// Identifier formats: device ids (SPEC §2.2), UUIDv7 message ids (SPEC §3), pair ids.

import { sha256, randomBytes } from "./crypto";
import { b64encode } from "./encoding";

export const DEVICE_ID = /^dev_[A-Za-z0-9_-]{22}$/;
export const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export const PAIR_ID = /^[A-Za-z0-9_-]{22}$/;

/** device_id = "dev_" || b64(SHA-256(sign_pub)[0..16]) */
export async function deviceIdFor(signPub: Uint8Array): Promise<string> {
  const digest = await sha256(signPub);
  return "dev_" + b64encode(digest.slice(0, 16));
}

export function newPairId(): string {
  return b64encode(randomBytes(16));
}
