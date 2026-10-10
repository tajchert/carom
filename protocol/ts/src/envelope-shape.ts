// Structural envelope checks (SPEC §3, §6 rule 1). Used by the Hub before it touches
// storage and by receivers before opening. No keys are needed.

import { b64decode } from './b64.ts';
import { AEAD_TAG_LENGTH, LIMITS, PROTOCOL_VERSION, SALT_LENGTH, SIG_LENGTH } from './constants.ts';
import { isDeviceId } from './device.ts';
import { isValidKv } from './seal.ts';
import { isUuidV7 } from './uuidv7.ts';

export interface Envelope {
  v: 1;
  msg_id: string;
  from: string;
  to: string[];
  collapse?: string;
  ttl_s: number;
  kv: number;
  salt: string;
  ct: string;
  sig: string;
}

export type ShapeErrorCode = 'bad_request' | 'too_large';

export type ShapeResult =
  | { ok: true; envelope: Envelope }
  | { ok: false; code: ShapeErrorCode; message: string };

const REQUIRED = ['v', 'msg_id', 'from', 'to', 'ttl_s', 'kv', 'salt', 'ct', 'sig'] as const;
const ALLOWED = new Set<string>([...REQUIRED, 'collapse']);
const COLLAPSE_LENGTH = 16;
const MAX_CT = LIMITS.maxPlaintext + AEAD_TAG_LENGTH;

class ShapeError extends Error {
  readonly code: ShapeErrorCode;
  constructor(code: ShapeErrorCode, message: string) {
    super(message);
    this.code = code;
  }
}

function fail(message: string, code: ShapeErrorCode = 'bad_request'): never {
  throw new ShapeError(code, message);
}

function decodeField(value: unknown, field: string): Uint8Array {
  if (typeof value !== 'string') fail(`${field} must be a string`);
  try {
    return b64decode(value);
  } catch {
    return fail(`${field} is not canonical base64url`);
  }
}

function check(value: unknown, serializedBytes: number | undefined): Envelope {
  if (serializedBytes !== undefined && serializedBytes > LIMITS.maxEnvelopeBytes) {
    fail('envelope exceeds 98304 bytes', 'too_large');
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) fail('envelope must be an object');
  const e = value as Record<string, unknown>;
  for (const key of Object.keys(e)) if (!ALLOWED.has(key)) fail(`unknown field ${key}`);
  for (const key of REQUIRED) if (!(key in e)) fail(`missing field ${key}`);

  if (e.v !== PROTOCOL_VERSION) fail('v must be 1');
  if (!isUuidV7(e.msg_id)) fail('msg_id must be a lowercase UUIDv7');
  if (!isDeviceId(e.from)) fail('from must be a device id');
  if (!Array.isArray(e.to) || e.to.length < 1 || e.to.length > LIMITS.maxRecipients) {
    fail('to must list 1 to 32 device ids');
  }
  const seen = new Set<string>();
  for (const id of e.to) {
    if (!isDeviceId(id)) fail('to must contain device ids');
    if (seen.has(id)) fail('to contains duplicates');
    if (id === e.from) fail('to must not contain from');
    seen.add(id);
  }
  if (e.collapse !== undefined && decodeField(e.collapse, 'collapse').length !== COLLAPSE_LENGTH) {
    fail('collapse must be 16 bytes');
  }
  if (!Number.isInteger(e.ttl_s) || (e.ttl_s as number) < LIMITS.minTtlS || (e.ttl_s as number) > LIMITS.maxTtlS) {
    fail('ttl_s must be an integer in 60..604800');
  }
  if (!isValidKv(e.kv)) fail('kv must be a u32 >= 1');
  if (decodeField(e.salt, 'salt').length !== SALT_LENGTH) fail('salt must be 32 bytes');
  if (decodeField(e.sig, 'sig').length !== SIG_LENGTH) fail('sig must be 64 bytes');
  const ctLength = decodeField(e.ct, 'ct').length;
  if (ctLength < AEAD_TAG_LENGTH) fail('ct is shorter than the AEAD tag');
  if (ctLength > MAX_CT) fail('ct exceeds the plaintext limit', 'too_large');
  return e as unknown as Envelope;
}

/**
 * Strict shape check. `serializedBytes` is the UTF-8 length of the received JSON, when known.
 * Error codes match the Hub API error codes (SPEC §6).
 */
export function validateEnvelopeShape(value: unknown, serializedBytes?: number): ShapeResult {
  try {
    return { ok: true, envelope: check(value, serializedBytes) };
  } catch (err) {
    if (err instanceof ShapeError) return { ok: false, code: err.code, message: err.message };
    throw err;
  }
}
