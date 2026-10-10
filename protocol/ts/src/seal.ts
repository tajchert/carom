// Seal: group-key encryption plus signature (SPEC §2.3). Shared by envelopes and records.

import { concat, lp, u64, utf8, type Bytes } from './bytes.ts';
import { KEY_LENGTH, LIMITS, SALT_LENGTH } from './constants.ts';
import { msgKey } from './kdf.ts';
import { aeadOpen, aeadSeal, ed25519Sign, ed25519Verify } from './primitives.ts';

export interface SealInput {
  tag: string;
  /** Ordered header fields H; each is lp()-encoded into the AAD. */
  header: Bytes[];
  plaintext: Bytes;
  groupKey: Bytes;
  kv: number;
  signSk: Bytes;
  /** Fresh 32 random bytes. */
  salt: Bytes;
}

export interface SealOutput {
  aad: Bytes;
  ct: Bytes;
  sig: Bytes;
}

export function isValidKv(kv: unknown): kv is number {
  return typeof kv === 'number' && Number.isInteger(kv) && kv >= 1 && kv <= LIMITS.maxKv;
}

/** `aad = lp(tag) || lp(H[0]) || ... || lp(u64(kv)) || lp(salt)` */
export function sealAad(tag: string, header: Bytes[], kv: number, salt: Bytes): Bytes {
  return concat(lp(utf8(tag)), ...header.map((h) => lp(h)), lp(u64(kv)), lp(salt));
}

/** The signed bytes: `aad || lp(ct)`. */
export function sealSigInput(aad: Bytes, ct: Bytes): Bytes {
  return concat(aad, lp(ct));
}

export function seal(input: SealInput): SealOutput {
  if (!isValidKv(input.kv)) throw new RangeError('kv must be a u32 >= 1');
  if (input.salt.length !== SALT_LENGTH) throw new RangeError('salt must be 32 bytes');
  if (input.groupKey.length !== KEY_LENGTH) throw new RangeError('group key must be 32 bytes');
  const aad = sealAad(input.tag, input.header, input.kv, input.salt);
  const ct = aeadSeal(msgKey(input.groupKey, input.salt), aad, input.plaintext);
  const sig = ed25519Sign(input.signSk, sealSigInput(aad, ct));
  return { aad, ct, sig };
}

export function verifySeal(aad: Bytes, ct: Bytes, sig: Bytes, signPub: Bytes): boolean {
  return ed25519Verify(signPub, sealSigInput(aad, ct), sig);
}

/** Returns null when authentication fails. */
export function decryptSeal(aad: Bytes, ct: Bytes, groupKey: Bytes, salt: Bytes): Bytes | null {
  return aeadOpen(msgKey(groupKey, salt), aad, ct);
}
