// Envelope seal and open (SPEC §3, §2.3).

import { b64decode, b64encode } from './b64.ts';
import { concat, lp, u64, utf8, type Bytes } from './bytes.ts';
import { LIMITS, PROTOCOL_VERSION, TAG } from './constants.ts';
import { validateEnvelopeShape, type Envelope } from './envelope-shape.ts';
import { decodePayload, type Payload } from './payload.ts';
import { defaultRng, type Rng } from './primitives.ts';
import { decryptSeal, seal, sealAad, sealSigInput, verifySeal } from './seal.ts';

/** Group keys by version. */
export type GroupKeys = ReadonlyMap<number, Bytes>;

export interface EnvelopeHeader {
  msg_id: string;
  from: string;
  to: string[];
  collapse?: string;
  ttl_s: number;
}

/** `to_joined = lp(to[0]) || lp(to[1]) || ...` */
export function joinRecipients(to: readonly string[]): Bytes {
  return concat(...to.map((id) => lp(id)));
}

/** `H = [u64(v), msg_id, from, to_joined, collapse_or_empty, u64(ttl_s)]` */
export function envelopeHeaderFields(h: EnvelopeHeader): Bytes[] {
  return [
    u64(PROTOCOL_VERSION),
    utf8(h.msg_id),
    utf8(h.from),
    joinRecipients(h.to),
    utf8(h.collapse ?? ''),
    u64(h.ttl_s),
  ];
}

/** Rebuilds the AAD from an envelope's fields. Throws if `salt` is not valid base64url. */
export function envelopeAad(env: Envelope): Bytes {
  return sealAad(TAG.envelope, envelopeHeaderFields(env), env.kv, b64decode(env.salt));
}

/** The bytes covered by `sig`: `aad || lp(ct)`. */
export function envelopeSigInput(env: Envelope): Bytes {
  return sealSigInput(envelopeAad(env), b64decode(env.ct));
}

/** Verifies `sig`; false on malformed fields. The Hub uses this as defence in depth (SPEC §6). */
export function verifyEnvelopeSignature(env: Envelope, signPub: Bytes): boolean {
  try {
    return verifySeal(envelopeAad(env), b64decode(env.ct), b64decode(env.sig), signPub);
  } catch {
    return false;
  }
}

export interface SealEnvelopeInput extends EnvelopeHeader {
  /** Encoded (normally padded) payload bytes, see `encodePayload`. */
  plaintext: Bytes;
  groupKey: Bytes;
  kv: number;
  signSk: Bytes;
  /** Explicit 32-byte salt; otherwise drawn from `rng`. */
  salt?: Bytes;
  rng?: Rng;
}

/** Seals and returns the envelope with fields in canonical order. Throws on invalid input. */
export function sealEnvelope(input: SealEnvelopeInput): Envelope {
  if (input.plaintext.length > LIMITS.maxPlaintext) throw new RangeError('payload exceeds 65536 bytes');
  const salt = input.salt ?? (input.rng ?? defaultRng)(32);
  const { ct, sig } = seal({
    tag: TAG.envelope,
    header: envelopeHeaderFields(input),
    plaintext: input.plaintext,
    groupKey: input.groupKey,
    kv: input.kv,
    signSk: input.signSk,
    salt,
  });
  const env: Envelope = {
    v: PROTOCOL_VERSION,
    msg_id: input.msg_id,
    from: input.from,
    to: [...input.to],
    ...(input.collapse !== undefined ? { collapse: input.collapse } : {}),
    ttl_s: input.ttl_s,
    kv: input.kv,
    salt: b64encode(salt),
    ct: b64encode(ct),
    sig: b64encode(sig),
  };
  const shape = validateEnvelopeShape(env);
  if (!shape.ok) throw new RangeError(`invalid envelope: ${shape.message}`);
  return env;
}

export type OpenEnvelopeError = 'bad_shape' | 'bad_signature' | 'unknown_kv' | 'decrypt_failed' | 'bad_payload';

export type OpenEnvelopeResult =
  | { ok: true; payload: Payload; plaintext: Bytes }
  | { ok: false; error: OpenEnvelopeError };

/**
 * Opens an envelope in the SPEC §2.3 order: shape, signature (with the sender's
 * `sign_pub`), key lookup by `kv`, decryption, then payload parsing.
 */
export function openEnvelope(env: unknown, signPub: Bytes, keys: GroupKeys): OpenEnvelopeResult {
  const shape = validateEnvelopeShape(env);
  if (!shape.ok) return { ok: false, error: 'bad_shape' };
  const e = shape.envelope;
  const aad = envelopeAad(e);
  const ct = b64decode(e.ct);
  if (!verifySeal(aad, ct, b64decode(e.sig), signPub)) return { ok: false, error: 'bad_signature' };
  const groupKey = keys.get(e.kv);
  if (groupKey === undefined) return { ok: false, error: 'unknown_kv' };
  const plaintext = decryptSeal(aad, ct, groupKey, b64decode(e.salt));
  if (plaintext === null) return { ok: false, error: 'decrypt_failed' };
  const payload = decodePayload(plaintext);
  if (payload === null) return { ok: false, error: 'bad_payload' };
  return { ok: true, payload, plaintext };
}
