// Payload wrapper and padding (SPEC §3.1).

import { fromUtf8, utf8, type Bytes } from './bytes.ts';
import { LIMITS } from './constants.ts';
import { isUuidV7 } from './uuidv7.ts';

export interface Payload<B = Record<string, unknown>> {
  type: string;
  /** Sender clock at send time, Unix ms. */
  ts: number;
  /** msg_id of the message this replies to. */
  reply_to?: string;
  body: B;
}

const SMALL_BUCKET = 256;
const LARGE_BUCKET = 4096;
const SPACE = 0x20;

/**
 * Padded size for an `n`-byte payload: the next multiple of 256 up to 4096, the next
 * multiple of 4096 above that. Throws when `n` exceeds the 65 536-byte plaintext limit.
 */
export function paddedLength(n: number): number {
  if (!Number.isInteger(n) || n < 0) throw new RangeError('length must be a non-negative integer');
  if (n > LIMITS.maxPlaintext) throw new RangeError('payload exceeds 65536 bytes');
  const bucket = n <= LARGE_BUCKET ? SMALL_BUCKET : LARGE_BUCKET;
  return Math.ceil(n / bucket) * bucket;
}

/** Appends ASCII spaces up to `paddedLength`. */
export function padPayload(json: Bytes): Bytes {
  const out = new Uint8Array(paddedLength(json.length)).fill(SPACE);
  out.set(json);
  return out;
}

/** Serializes as `{type, ts, reply_to?, body}` and pads unless `pad` is false. */
export function encodePayload(payload: Payload<unknown>, options: { pad?: boolean } = {}): Bytes {
  const ordered: Record<string, unknown> = { type: payload.type, ts: payload.ts };
  if (payload.reply_to !== undefined) ordered.reply_to = payload.reply_to;
  ordered.body = payload.body;
  const json = utf8(JSON.stringify(ordered));
  if (options.pad === false) {
    if (json.length > LIMITS.maxPlaintext) throw new RangeError('payload exceeds 65536 bytes');
    return json;
  }
  return padPayload(json);
}

/** Parses and checks the wrapper fields; returns null on any malformed input. */
export function decodePayload(plaintext: Bytes): Payload | null {
  let value: unknown;
  try {
    value = JSON.parse(fromUtf8(plaintext));
  } catch {
    return null;
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null;
  const p = value as Record<string, unknown>;
  if (typeof p.type !== 'string' || p.type.length === 0) return null;
  if (!Number.isSafeInteger(p.ts) || (p.ts as number) < 0) return null;
  if (p.reply_to !== undefined && !isUuidV7(p.reply_to)) return null;
  if (typeof p.body !== 'object' || p.body === null || Array.isArray(p.body)) return null;
  return p as unknown as Payload;
}
