// Byte helpers and the SPEC §1 encodings lp() and u64().

export type Bytes = Uint8Array;

const encoder = new TextEncoder();
const strictDecoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });

export function utf8(s: string): Bytes {
  return encoder.encode(s);
}

/** Decodes UTF-8, throwing on malformed input. */
export function fromUtf8(b: Bytes): string {
  return strictDecoder.decode(b);
}

export function concat(...parts: Bytes[]): Bytes {
  let total = 0;
  for (const p of parts) total += p.length;
  const out = new Uint8Array(total);
  let off = 0;
  for (const p of parts) {
    out.set(p, off);
    off += p.length;
  }
  return out;
}

const U32_MAX = 0xffff_ffff;

export function u32be(n: number): Bytes {
  if (!Number.isInteger(n) || n < 0 || n > U32_MAX) throw new RangeError(`u32 out of range: ${n}`);
  const out = new Uint8Array(4);
  new DataView(out.buffer).setUint32(0, n, false);
  return out;
}

/** `u64(n)`: 8-byte big-endian unsigned integer. Accepts safe integers or bigint. */
export function u64(n: number | bigint): Bytes {
  const v = typeof n === 'bigint' ? n : BigInt(checkSafeUint(n));
  if (v < 0n || v > 0xffff_ffff_ffff_ffffn) throw new RangeError(`u64 out of range: ${n}`);
  const out = new Uint8Array(8);
  new DataView(out.buffer).setBigUint64(0, v, false);
  return out;
}

function checkSafeUint(n: number): number {
  if (!Number.isSafeInteger(n) || n < 0) throw new RangeError(`u64 out of range: ${n}`);
  return n;
}

/** `lp(x) = u32be(len(x)) || x`. Strings are UTF-8 encoded first. */
export function lp(x: Bytes | string): Bytes {
  const b = typeof x === 'string' ? utf8(x) : x;
  return concat(u32be(b.length), b);
}

/** Constant-time equality for equal-length inputs; false on length mismatch. */
export function equalBytes(a: Bytes, b: Bytes): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i]! ^ b[i]!;
  return diff === 0;
}

export function isAllZero(b: Bytes): boolean {
  let acc = 0;
  for (const x of b) acc |= x;
  return acc === 0;
}

export function toHex(b: Bytes): string {
  let s = '';
  for (const x of b) s += x.toString(16).padStart(2, '0');
  return s;
}

export function fromHex(hex: string): Bytes {
  if (hex.length % 2 !== 0 || !/^[0-9a-f]*$/.test(hex)) throw new Error('invalid hex');
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}
