// Rekey through the Hub keyring (SPEC §5.5).

import { b64decodeLength, b64encode } from './b64.ts';
import { concat, lp, u64, type Bytes } from './bytes.ts';
import { KEY_LENGTH, TAG } from './constants.ts';
import { isDeviceId, sortIds, type Identity } from './device.ts';
import { openBox, parseBox, sealBox, type Box } from './box.ts';
import { defaultRng, ed25519Sign, ed25519Verify, type Rng } from './primitives.ts';
import { trustedSenderKey } from './receive.ts';
import { isValidKv } from './seal.ts';

/** One keyring version: the `PUT /v1/keyring` body and an item of `GET /v1/keyring`. */
export interface KeyringVersion {
  kv: number;
  by: string;
  boxes: Record<string, Box>;
  sig: string;
}

/** `aad = lp("carom/v1/rekey") || lp(R.id) || lp(D.id) || lp(u64(kv'))` */
export function rekeyBoxAad(byId: string, rcptId: string, kv: number): Bytes {
  return concat(lp(TAG.rekey), lp(byId), lp(rcptId), lp(u64(kv)));
}

/**
 * `lp("carom/v1/rekey") || lp(R.id) || u64(kv') || lp(D.id) || lp(epk) || lp(ct) || ...`
 * over ids sorted ascending. `epk` and `ct` are the raw (decoded) bytes. Note u64(kv') is not
 * length-prefixed here, unlike in the box AAD. Throws on a malformed box.
 */
export function rekeySigInput(byId: string, kv: number, boxes: Readonly<Record<string, Box>>): Bytes {
  const parts: Bytes[] = [lp(TAG.rekey), lp(byId), u64(kv)];
  for (const id of sortIds(Object.keys(boxes))) {
    const parsed = parseBox(boxes[id]);
    if (parsed === null) throw new RangeError(`malformed box for ${id}`);
    parts.push(lp(id), lp(parsed.ephPub), lp(parsed.ct));
  }
  return concat(...parts);
}

export interface RekeyMember {
  id: string;
  kxPub: Bytes;
}

export interface BuildRekeyInput {
  by: Identity;
  /** The new version kv' = kv + 1. */
  kv: number;
  /** The new group key K'. */
  groupKey: Bytes;
  /** Remaining members, including the rekeying device itself. */
  members: RekeyMember[];
  /** Explicit ephemeral secrets per member id; otherwise drawn from `rng`. */
  ephSks?: ReadonlyMap<string, Bytes>;
  rng?: Rng;
}

export function buildRekey(input: BuildRekeyInput): KeyringVersion {
  if (!isValidKv(input.kv) || input.kv < 2) throw new RangeError('rekey kv must be >= 2');
  if (input.groupKey.length !== KEY_LENGTH) throw new RangeError('group key must be 32 bytes');
  if (!input.members.some((m) => m.id === input.by.deviceId)) throw new RangeError('members must include the rekeying device');
  const rng = input.rng ?? defaultRng;
  const boxes: Record<string, Box> = {};
  for (const m of input.members) {
    if (!isDeviceId(m.id) || m.id in boxes) throw new RangeError(`invalid or duplicate member ${m.id}`);
    boxes[m.id] = sealBox({
      rcptKxPub: m.kxPub,
      aad: rekeyBoxAad(input.by.deviceId, m.id, input.kv),
      plaintext: input.groupKey,
      ephSk: input.ephSks?.get(m.id) ?? rng(32),
    });
  }
  const sig = ed25519Sign(input.by.signSk, rekeySigInput(input.by.deviceId, input.kv, boxes));
  return { kv: input.kv, by: input.by.deviceId, boxes, sig: b64encode(sig) };
}

/** Structural check of a keyring version. */
export function isKeyringVersionShape(v: unknown): v is KeyringVersion {
  if (typeof v !== 'object' || v === null || Array.isArray(v)) return false;
  const r = v as Record<string, unknown>;
  if (!isValidKv(r.kv) || !isDeviceId(r.by) || b64decodeLength(r.sig, 64) === null) return false;
  if (typeof r.boxes !== 'object' || r.boxes === null || Array.isArray(r.boxes)) return false;
  const entries = Object.entries(r.boxes);
  return entries.length > 0 && entries.every(([id, box]) => isDeviceId(id) && parseBox(box) !== null);
}

/** Verifies `sig` over every box with the rekeying device's `sign_pub`. */
export function verifyRekeySignature(version: KeyringVersion, bySignPub: Bytes): boolean {
  const sig = b64decodeLength(version.sig, 64);
  if (sig === null) return false;
  try {
    return ed25519Verify(bySignPub, rekeySigInput(version.by, version.kv, version.boxes), sig);
  } catch {
    return false;
  }
}

export type OpenRekeyError = 'bad_shape' | 'untrusted_signer' | 'bad_signature' | 'no_box' | 'decrypt_failed' | 'bad_key';

export type OpenRekeyResult = { ok: true; kv: number; groupKey: Bytes } | { ok: false; error: OpenRekeyError };

export interface OpenRekeyContext {
  selfId: string;
  kxSk: Bytes;
  /** Members under the previous key: id → sign_pub. */
  directory: ReadonlyMap<string, Bytes>;
}

/** Device-side processing of one keyring version (SPEC §5.5 steps 1–3). */
export function openRekey(value: unknown, ctx: OpenRekeyContext): OpenRekeyResult {
  if (!isKeyringVersionShape(value)) return { ok: false, error: 'bad_shape' };
  const bySignPub = trustedSenderKey(ctx.directory, value.by);
  if (bySignPub === null) return { ok: false, error: 'untrusted_signer' };
  if (!verifyRekeySignature(value, bySignPub)) return { ok: false, error: 'bad_signature' };
  const box = Object.hasOwn(value.boxes, ctx.selfId) ? value.boxes[ctx.selfId] : undefined;
  if (box === undefined) return { ok: false, error: 'no_box' };
  const key = openBox(box, { kxSk: ctx.kxSk, aad: rekeyBoxAad(value.by, ctx.selfId, value.kv) });
  if (key === null) return { ok: false, error: 'decrypt_failed' };
  if (key.length !== KEY_LENGTH) return { ok: false, error: 'bad_key' };
  return { ok: true, kv: value.kv, groupKey: key };
}
