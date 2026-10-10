// Deterministic inputs shared by all vector files. Every secret is
// SHA-256("carom/v1/test-vectors/" || label), so other implementations can rebuild them.

import { b64encode } from '../../src/b64.ts';
import { concat, toHex, u32be, utf8, type Bytes } from '../../src/bytes.ts';
import { identityFromSecrets, type Identity } from '../../src/device.ts';
import { sha256, type Rng } from '../../src/primitives.ts';
import { formatUuidV7 } from '../../src/uuidv7.ts';

export const SEED_PREFIX = 'carom/v1/test-vectors/';

export function seed(label: string): Bytes {
  return sha256(utf8(SEED_PREFIX + label));
}

/** Deterministic byte stream: block i = SHA-256(prefix || label || "/" || u32be(i)). */
export function seededRng(label: string): Rng {
  let counter = 0;
  let buffer: Bytes = new Uint8Array(0);
  return (length) => {
    while (buffer.length < length) {
      buffer = concat(buffer, sha256(concat(utf8(SEED_PREFIX + label + '/'), u32be(counter++))));
    }
    const out = buffer.slice(0, length);
    buffer = buffer.slice(length);
    return out;
  };
}

export const hex = toHex;
export const b64 = b64encode;

export function identity(name: string): Identity {
  return identityFromSecrets(seed(`${name}.sign`), seed(`${name}.kx`));
}

export const PHONE = identity('phone');
export const MAC = identity('mac');
export const LAPTOP = identity('laptop');
export const TABLET = identity('tablet');
export const OUTSIDER = identity('outsider');

export const K1 = seed('group-key.1');
export const K2 = seed('group-key.2');

/** Base time: 2026-10-10T16:00:00Z (the SPEC example timestamp). */
export const T0 = 1_791_648_000_000;

export function msgId(label: string, ms: number): string {
  const r = seed(`msg-id/${label}`);
  return formatUuidV7(ms, ((r[0]! << 8) | r[1]!) & 0x7ff, r.subarray(2, 10));
}

export function identityJson(id: Identity): Record<string, string> {
  return {
    device_id: id.deviceId,
    sign_sk_hex: hex(id.signSk),
    sign_pub_hex: hex(id.signPub),
    sign_pub_b64: b64(id.signPub),
    kx_sk_hex: hex(id.kxSk),
    kx_pub_hex: hex(id.kxPub),
    kx_pub_b64: b64(id.kxPub),
  };
}

export const SIG_NOTE =
  'Signatures are Ed25519 per RFC 8032 and deterministic ("deterministic_rfc8032"). Implementations whose signer is randomized (e.g. CryptoKit) MUST check them by verification, not by byte comparison.';

export interface VectorFile {
  description: string;
  [key: string]: unknown;
}
