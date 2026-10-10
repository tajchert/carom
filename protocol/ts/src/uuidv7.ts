// Monotonic UUIDv7 msg_ids (SPEC §3.1), RFC 9562 §6.2 method 1: a 12-bit counter in
// rand_a, reseeded with its top bit clear on every new millisecond. On counter overflow,
// or when the clock goes backwards, the timestamp is advanced past the last one used.

import { toHex } from './bytes.ts';
import { defaultRng, type Rng } from './primitives.ts';

const UUID_V7_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const MAX_MS = 2 ** 48 - 1;
const COUNTER_MAX = 0xfff;

/** Lowercase, hyphenated UUID with version 7 and the RFC 9562 variant. */
export function isUuidV7(s: unknown): s is string {
  return typeof s === 'string' && UUID_V7_RE.test(s);
}

/** The 48-bit Unix millisecond timestamp of a UUIDv7. */
export function uuidV7Timestamp(id: string): number {
  if (!isUuidV7(id)) throw new Error('not a UUIDv7');
  return parseInt(id.slice(0, 8) + id.slice(9, 13), 16);
}

/** Orders msg_ids; for valid lowercase UUIDv7 strings this is plain string order. */
export function compareMsgIds(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

export interface UuidV7Options {
  now?: () => number;
  rng?: Rng;
}

/** Formats a UUIDv7 from its parts; `randB` is 8 bytes of which the low 62 bits are used. */
export function formatUuidV7(ms: number, counter: number, randB: Uint8Array): string {
  if (!Number.isInteger(ms) || ms < 0 || ms > MAX_MS) throw new RangeError('timestamp out of range');
  if (!Number.isInteger(counter) || counter < 0 || counter > COUNTER_MAX) throw new RangeError('counter out of range');
  if (randB.length !== 8) throw new RangeError('randB must be 8 bytes');
  const bytes = new Uint8Array(16);
  let t = ms;
  for (let i = 5; i >= 0; i--) {
    bytes[i] = t % 256;
    t = Math.floor(t / 256);
  }
  bytes[6] = 0x70 | (counter >> 8);
  bytes[7] = counter & 0xff;
  bytes.set(randB, 8);
  bytes[8] = 0x80 | (bytes[8]! & 0x3f);
  const h = toHex(bytes);
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** Returns a generator whose ids are strictly increasing across calls. */
export function createUuidV7Generator(options: UuidV7Options = {}): () => string {
  const now = options.now ?? (() => Date.now());
  const rng = options.rng ?? defaultRng;
  let lastMs = -1;
  let counter = 0;
  return () => {
    const r = rng(10);
    const seed = ((r[0]! << 8) | r[1]!) & 0x7ff;
    let ms = Math.floor(now());
    if (ms > lastMs) {
      counter = seed;
    } else {
      ms = lastMs;
      counter++;
      if (counter > COUNTER_MAX) {
        ms = lastMs + 1;
        counter = seed;
      }
    }
    lastMs = ms;
    return formatUuidV7(ms, counter, r.subarray(2, 10));
  };
}
