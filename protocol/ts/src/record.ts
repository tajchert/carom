// Device records (SPEC §5.1, §5.3): sealed with tag "carom/v1/rec" and H = [id, u64(updated)].

import { b64decode, b64decodeLength, b64encode } from './b64.ts';
import { equalBytes, fromUtf8, u64, utf8, type Bytes } from './bytes.ts';
import { AEAD_TAG_LENGTH, SALT_LENGTH, SIG_LENGTH, TAG } from './constants.ts';
import { deviceIdMatches, isDeviceId } from './device.ts';
import { defaultRng, ed25519PublicKey, type Rng } from './primitives.ts';
import { decryptSeal, isValidKv, seal, sealAad, verifySeal } from './seal.ts';

export type DeviceKind = 'phone' | 'desktop' | 'cli';

/** `"*"` (every device) or the allowed sender ids. */
export type ReceiveRule = '*' | string[];

export interface DeviceRecord {
  id: string;
  name: string;
  kind: DeviceKind;
  platform: string;
  app_version: string;
  sign_pub: string;
  kx_pub: string;
  caps: string[];
  receive: Record<string, ReceiveRule>;
  updated: number;
}

export interface SealedRecord {
  id: string;
  updated: number;
  kv: number;
  salt: string;
  ct: string;
  sig: string;
}

const KINDS: readonly string[] = ['phone', 'desktop', 'cli'];
const SEALED_KEYS = ['id', 'updated', 'kv', 'salt', 'ct', 'sig'];

const isStr = (v: unknown): v is string => typeof v === 'string';
const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const isUpdated = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) >= 0;

function isReceiveRule(v: unknown): v is ReceiveRule {
  return v === '*' || (Array.isArray(v) && v.every(isDeviceId));
}

/** Checks known fields of a record plaintext; unknown fields are allowed. */
export function isValidDeviceRecord(v: unknown): v is DeviceRecord {
  return (
    isObj(v) &&
    isDeviceId(v.id) &&
    isStr(v.name) &&
    isStr(v.kind) &&
    KINDS.includes(v.kind) &&
    isStr(v.platform) &&
    isStr(v.app_version) &&
    b64decodeLength(v.sign_pub, 32) !== null &&
    b64decodeLength(v.kx_pub, 32) !== null &&
    Array.isArray(v.caps) &&
    v.caps.every(isStr) &&
    isObj(v.receive) &&
    Object.values(v.receive).every(isReceiveRule) &&
    isUpdated(v.updated)
  );
}

/** Strict check of the published (sealed) form. */
export function isSealedRecordShape(v: unknown): v is SealedRecord {
  if (!isObj(v)) return false;
  const keys = Object.keys(v);
  if (keys.length !== SEALED_KEYS.length || !SEALED_KEYS.every((k) => keys.includes(k))) return false;
  if (!isDeviceId(v.id) || !isUpdated(v.updated) || !isValidKv(v.kv)) return false;
  if (b64decodeLength(v.salt, SALT_LENGTH) === null || b64decodeLength(v.sig, SIG_LENGTH) === null) return false;
  try {
    return isStr(v.ct) && b64decode(v.ct).length >= AEAD_TAG_LENGTH;
  } catch {
    return false;
  }
}

/** Serializes in the canonical field order. */
export function encodeRecord(r: DeviceRecord): Bytes {
  const ordered: DeviceRecord = {
    id: r.id,
    name: r.name,
    kind: r.kind,
    platform: r.platform,
    app_version: r.app_version,
    sign_pub: r.sign_pub,
    kx_pub: r.kx_pub,
    caps: r.caps,
    receive: r.receive,
    updated: r.updated,
  };
  return utf8(JSON.stringify(ordered));
}

export function recordAad(id: string, updated: number, kv: number, salt: Bytes): Bytes {
  return sealAad(TAG.record, [utf8(id), u64(updated)], kv, salt);
}

export interface SealRecordInput {
  record: DeviceRecord;
  groupKey: Bytes;
  kv: number;
  signSk: Bytes;
  salt?: Bytes;
  rng?: Rng;
}

/** Seals a record; throws if it is invalid or not signed by the key it names. */
export function sealRecord(input: SealRecordInput): SealedRecord {
  const r = input.record;
  if (!isValidDeviceRecord(r)) throw new RangeError('invalid device record');
  const signPub = ed25519PublicKey(input.signSk);
  if (!equalBytes(signPub, b64decode(r.sign_pub)) || !deviceIdMatches(r.id, signPub)) {
    throw new RangeError('record id/sign_pub do not match the signing key');
  }
  const salt = input.salt ?? (input.rng ?? defaultRng)(SALT_LENGTH);
  const { ct, sig } = seal({
    tag: TAG.record,
    header: [utf8(r.id), u64(r.updated)],
    plaintext: encodeRecord(r),
    groupKey: input.groupKey,
    kv: input.kv,
    signSk: input.signSk,
    salt,
  });
  return { id: r.id, updated: r.updated, kv: input.kv, salt: b64encode(salt), ct: b64encode(ct), sig: b64encode(sig) };
}

export type OpenRecordError =
  | 'bad_shape'
  | 'old_kv'
  | 'unknown_kv'
  | 'decrypt_failed'
  | 'bad_record'
  | 'id_mismatch'
  | 'updated_mismatch'
  | 'device_id_mismatch'
  | 'bad_signature'
  | 'rollback';

export type OpenRecordResult = { ok: true; record: DeviceRecord } | { ok: false; error: OpenRecordError };

export interface OpenRecordContext {
  /** The current group key and its version. Only records under it are trusted. */
  groupKey: Bytes;
  kv: number;
  /** Highest `updated` already accepted for this id. */
  lastUpdated?: number;
}

/**
 * Opens a record and applies the SPEC §5.3 membership checks. The signer key lives inside
 * the plaintext, so decryption necessarily precedes signature verification here.
 */
export function openRecord(value: unknown, ctx: OpenRecordContext): OpenRecordResult {
  if (!isSealedRecordShape(value)) return { ok: false, error: 'bad_shape' };
  if (value.kv < ctx.kv) return { ok: false, error: 'old_kv' };
  if (value.kv > ctx.kv) return { ok: false, error: 'unknown_kv' };

  const salt = b64decode(value.salt);
  const ct = b64decode(value.ct);
  const aad = recordAad(value.id, value.updated, value.kv, salt);
  const plaintext = decryptSeal(aad, ct, ctx.groupKey, salt);
  if (plaintext === null) return { ok: false, error: 'decrypt_failed' };

  let record: unknown;
  try {
    record = JSON.parse(fromUtf8(plaintext));
  } catch {
    return { ok: false, error: 'bad_record' };
  }
  if (!isValidDeviceRecord(record)) return { ok: false, error: 'bad_record' };
  if (record.id !== value.id) return { ok: false, error: 'id_mismatch' };
  if (record.updated !== value.updated) return { ok: false, error: 'updated_mismatch' };
  const signPub = b64decode(record.sign_pub);
  if (!deviceIdMatches(record.id, signPub)) return { ok: false, error: 'device_id_mismatch' };
  if (!verifySeal(aad, ct, b64decode(value.sig), signPub)) return { ok: false, error: 'bad_signature' };
  if (ctx.lastUpdated !== undefined && value.updated < ctx.lastUpdated) return { ok: false, error: 'rollback' };
  return { ok: true, record };
}
