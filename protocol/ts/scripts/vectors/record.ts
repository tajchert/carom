// Device record seal/open vectors (SPEC §5.1, §5.3).

import { b64decode } from '../../src/b64.ts';
import { fromHex, u64, utf8, type Bytes } from '../../src/bytes.ts';
import { TAG } from '../../src/constants.ts';
import type { Identity } from '../../src/device.ts';
import { msgKey } from '../../src/kdf.ts';
import { encodeRecord, openRecord, recordAad, sealRecord, type DeviceRecord, type SealedRecord } from '../../src/record.ts';
import { defaultReceive } from '../../src/routing.ts';
import { seal, sealSigInput } from '../../src/seal.ts';
import { b64, hex, K1, K2, MAC, OUTSIDER, PHONE, seed, SIG_NOTE, T0, type VectorFile } from './fixtures.ts';

function phoneRecord(updated: number, signer: Identity = PHONE): DeviceRecord {
  return {
    id: PHONE.deviceId,
    name: "Michał's Pixel",
    kind: 'phone',
    platform: 'android',
    app_version: '1.0.0',
    sign_pub: b64(signer.signPub),
    kx_pub: b64(PHONE.kxPub),
    caps: ['notification.dismiss@1', 'clipboard.set@1'],
    receive: defaultReceive('phone') as DeviceRecord['receive'],
    updated,
  };
}

interface CraftInput {
  outerId: string;
  outerUpdated: number;
  plaintext: Bytes;
  signer: Identity;
  groupKey: Bytes;
  kv: number;
  label: string;
}

/** Low-level seal that allows inconsistent outer/inner fields for negative vectors. */
function craft(c: CraftInput): SealedRecord {
  const salt = seed(`salt/${c.label}`);
  const { ct, sig } = seal({
    tag: TAG.record,
    header: [utf8(c.outerId), u64(c.outerUpdated)],
    plaintext: c.plaintext,
    groupKey: c.groupKey,
    kv: c.kv,
    signSk: c.signer.signSk,
    salt,
  });
  return { id: c.outerId, updated: c.outerUpdated, kv: c.kv, salt: b64(salt), ct: b64(ct), sig: b64(sig) };
}

export function recordVectors(): VectorFile {
  const record = phoneRecord(T0);
  const salt = seed('salt/record');
  const sealed = sealRecord({ record, groupKey: K2, kv: 2, signSk: PHONE.signSk, salt });
  const aad = recordAad(record.id, record.updated, 2, salt);
  const ct = b64decode(sealed.ct);
  const good = (label: string, patch: Partial<CraftInput>) =>
    craft({
      outerId: PHONE.deviceId,
      outerUpdated: T0,
      plaintext: encodeRecord(record),
      signer: PHONE,
      groupKey: K2,
      kv: 2,
      label,
      ...patch,
    });
  const ctx = { group_key_hex: hex(K2), kv: 2, last_updated: null as number | null };

  const cases: Array<{ name: string; sealed: unknown; context: typeof ctx; expected: string }> = [
    { name: 'valid', sealed, context: ctx, expected: 'ok' },
    { name: 'valid, last_updated equal', sealed, context: { ...ctx, last_updated: T0 }, expected: 'ok' },
    { name: 'rollback: last_updated is newer', sealed, context: { ...ctx, last_updated: T0 + 1 }, expected: 'rollback' },
    { name: 'sealed under the previous kv', sealed: good('rec-old-kv', { groupKey: K1, kv: 1 }), context: ctx, expected: 'old_kv' },
    { name: 'sealed under a future kv', sealed: good('rec-future-kv', { groupKey: K2, kv: 3 }), context: ctx, expected: 'unknown_kv' },
    { name: 'wrong group key for kv 2', sealed: good('rec-wrong-key', { groupKey: K1 }), context: ctx, expected: 'decrypt_failed' },
    { name: 'outer updated tampered', sealed: { ...sealed, updated: T0 + 1 }, context: ctx, expected: 'decrypt_failed' },
    { name: 'outer id tampered', sealed: { ...sealed, id: MAC.deviceId }, context: ctx, expected: 'decrypt_failed' },
    { name: 'inner id differs from outer id', sealed: good('rec-id', { outerId: MAC.deviceId }), context: ctx, expected: 'id_mismatch' },
    {
      name: 'inner updated differs from outer updated',
      sealed: good('rec-updated', { outerUpdated: T0 + 1 }),
      context: ctx,
      expected: 'updated_mismatch',
    },
    {
      name: 'sign_pub does not derive the record id',
      sealed: good('rec-devid', { plaintext: encodeRecord(phoneRecord(T0, OUTSIDER)), signer: OUTSIDER }),
      context: ctx,
      expected: 'device_id_mismatch',
    },
    { name: 'signed by a key other than sign_pub', sealed: good('rec-signer', { signer: OUTSIDER }), context: ctx, expected: 'bad_signature' },
    {
      name: 'sig first bit flipped',
      sealed: { ...sealed, sig: b64(b64decode(sealed.sig).map((x, i) => (i === 0 ? x ^ 1 : x))) },
      context: ctx,
      expected: 'bad_signature',
    },
    { name: 'plaintext is not a record', sealed: good('rec-bad', { plaintext: utf8('{"id":"x"}') }), context: ctx, expected: 'bad_record' },
    { name: 'unknown field in the sealed form', sealed: { ...sealed, extra: true }, context: ctx, expected: 'bad_shape' },
  ];

  for (const c of cases) {
    const r = openRecord(c.sealed, {
      groupKey: fromHex(c.context.group_key_hex),
      kv: c.context.kv,
      ...(c.context.last_updated !== null ? { lastUpdated: c.context.last_updated } : {}),
    });
    const got = r.ok ? 'ok' : r.error;
    if (got !== c.expected) throw new Error(`record vector "${c.name}": expected ${c.expected}, got ${got}`);
  }

  return {
    description:
      'Device records (SPEC §5.1, §5.3). Sealed with tag "carom/v1/rec" and H = [id, u64(updated)]. The plaintext is the record JSON (not padded). Opening order: strict sealed shape (bad_shape); kv must equal the current kv (old_kv if lower, unknown_kv if higher); decrypt (decrypt_failed); parse and validate the record (bad_record); inner id == outer id (id_mismatch); inner updated == outer updated (updated_mismatch); device_id(sign_pub) == id (device_id_mismatch); verify sig with the sign_pub inside the record (bad_signature); updated >= highest updated already accepted (rollback; equal is accepted). ' +
      SIG_NOTE,
    seal: {
      record,
      record_json: JSON.stringify(record),
      plaintext_hex: hex(encodeRecord(record)),
      signer_sign_sk_hex: hex(PHONE.signSk),
      group_key_hex: hex(K2),
      kv: 2,
      salt_hex: hex(salt),
      msg_key_hex: hex(msgKey(K2, salt)),
      header_fields_hex: [hex(utf8(record.id)), hex(u64(record.updated))],
      aad_hex: hex(aad),
      ct_hex: hex(ct),
      sig_input_hex: hex(sealSigInput(aad, ct)),
      sig_hex: hex(b64decode(sealed.sig)),
      sig_kind: 'deterministic_rfc8032',
      sealed,
    },
    open_cases: cases,
  };
}
