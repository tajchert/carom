// base64url without padding (RFC 4648 §5), SPEC §1. Decoding is strict: no padding,
// no characters outside the alphabet, no whitespace, and canonical trailing bits.

import type { Bytes } from './bytes.ts';

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
const LOOKUP = new Int16Array(128).fill(-1);
for (let i = 0; i < ALPHABET.length; i++) LOOKUP[ALPHABET.charCodeAt(i)] = i;

export function b64encode(data: Bytes): string {
  let out = '';
  let i = 0;
  for (; i + 3 <= data.length; i += 3) {
    const n = (data[i]! << 16) | (data[i + 1]! << 8) | data[i + 2]!;
    out += ALPHABET[(n >> 18) & 63]! + ALPHABET[(n >> 12) & 63]! + ALPHABET[(n >> 6) & 63]! + ALPHABET[n & 63]!;
  }
  const rest = data.length - i;
  if (rest === 1) {
    const n = data[i]! << 16;
    out += ALPHABET[(n >> 18) & 63]! + ALPHABET[(n >> 12) & 63]!;
  } else if (rest === 2) {
    const n = (data[i]! << 16) | (data[i + 1]! << 8);
    out += ALPHABET[(n >> 18) & 63]! + ALPHABET[(n >> 12) & 63]! + ALPHABET[(n >> 6) & 63]!;
  }
  return out;
}

export class B64Error extends Error {
  override name = 'B64Error';
}

function sextet(s: string, i: number): number {
  const c = s.charCodeAt(i);
  const v = c < 128 ? LOOKUP[c]! : -1;
  if (v < 0) throw new B64Error(`invalid base64url character at ${i}`);
  return v;
}

/** Strict decode. Throws B64Error on any non-canonical input. */
export function b64decode(s: string): Bytes {
  if (s.length % 4 === 1) throw new B64Error('invalid base64url length');
  const fullGroups = Math.floor(s.length / 4);
  const rest = s.length % 4;
  const out = new Uint8Array(fullGroups * 3 + (rest === 0 ? 0 : rest - 1));
  let o = 0;
  let i = 0;
  for (let g = 0; g < fullGroups; g++, i += 4) {
    const n = (sextet(s, i) << 18) | (sextet(s, i + 1) << 12) | (sextet(s, i + 2) << 6) | sextet(s, i + 3);
    out[o++] = (n >> 16) & 255;
    out[o++] = (n >> 8) & 255;
    out[o++] = n & 255;
  }
  if (rest === 2) {
    const a = sextet(s, i);
    const b = sextet(s, i + 1);
    if ((b & 0x0f) !== 0) throw new B64Error('non-canonical trailing bits');
    out[o++] = (a << 2) | (b >> 4);
  } else if (rest === 3) {
    const a = sextet(s, i);
    const b = sextet(s, i + 1);
    const c = sextet(s, i + 2);
    if ((c & 0x03) !== 0) throw new B64Error('non-canonical trailing bits');
    out[o++] = (a << 2) | (b >> 4);
    out[o++] = ((b & 0x0f) << 4) | (c >> 2);
  }
  return out;
}

/** Decodes and checks the byte length; returns null instead of throwing. */
export function b64decodeLength(s: unknown, length: number): Bytes | null {
  if (typeof s !== 'string') return null;
  try {
    const b = b64decode(s);
    return b.length === length ? b : null;
  } catch {
    return null;
  }
}
