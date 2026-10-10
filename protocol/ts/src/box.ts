// Box: encrypt to one device's X25519 key (SPEC §2.4).

import { b64decode, b64decodeLength, b64encode } from './b64.ts';
import type { Bytes } from './bytes.ts';
import { AEAD_TAG_LENGTH } from './constants.ts';
import { boxKey } from './kdf.ts';
import { aeadOpen, aeadSeal, defaultRng, x25519PublicKey, x25519Shared, type Rng } from './primitives.ts';

export interface Box {
  epk: string;
  ct: string;
}

const EMPTY = new Uint8Array(0);

export interface SealBoxInput {
  rcptKxPub: Bytes;
  aad: Bytes;
  plaintext: Bytes;
  /** Optional pre-shared key appended to the shared secret (empty by default). */
  psk?: Bytes;
  /** Explicit ephemeral X25519 secret; otherwise drawn from `rng`. */
  ephSk?: Bytes;
  rng?: Rng;
}

export function sealBox(input: SealBoxInput): Box {
  const ephSk = input.ephSk ?? (input.rng ?? defaultRng)(32);
  const ephPub = x25519PublicKey(ephSk);
  const ss = x25519Shared(ephSk, input.rcptKxPub);
  if (ss === null) throw new RangeError('invalid recipient kx_pub');
  const key = boxKey(ss, input.psk ?? EMPTY, ephPub, input.rcptKxPub);
  return { epk: b64encode(ephPub), ct: b64encode(aeadSeal(key, input.aad, input.plaintext)) };
}

export interface OpenBoxInput {
  kxSk: Bytes;
  aad: Bytes;
  psk?: Bytes;
}

/** Returns the plaintext, or null on a malformed box, all-zero secret, or failed decryption. */
export function openBox(box: unknown, input: OpenBoxInput): Bytes | null {
  const parsed = parseBox(box);
  if (parsed === null) return null;
  const ss = x25519Shared(input.kxSk, parsed.ephPub);
  if (ss === null) return null;
  const key = boxKey(ss, input.psk ?? EMPTY, parsed.ephPub, x25519PublicKey(input.kxSk));
  return aeadOpen(key, input.aad, parsed.ct);
}

/** Decodes `{epk, ct}` strictly; null when malformed. */
export function parseBox(box: unknown): { ephPub: Bytes; ct: Bytes } | null {
  if (typeof box !== 'object' || box === null || Array.isArray(box)) return null;
  const b = box as Record<string, unknown>;
  if (Object.keys(b).length !== 2) return null;
  const ephPub = b64decodeLength(b.epk, 32);
  if (ephPub === null || typeof b.ct !== 'string') return null;
  try {
    const ct = b64decode(b.ct);
    return ct.length >= AEAD_TAG_LENGTH ? { ephPub, ct } : null;
  } catch {
    return null;
  }
}
