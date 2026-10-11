// (a) The committed vectors are exactly what the generator produces, and (b) the
// reference implementation consumes them the way other implementations must.

import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { buildVectors, serializeVectorFile } from '../scripts/vectors/index.ts';
import { contextFromJson } from '../scripts/vectors/envelope.ts';
import {
  b64decode,
  b64encode,
  blobAad,
  collapseValue,
  convergentKey,
  deviceIdFromSignPub,
  fromHex,
  identityFromSecrets,
  isDeviceId,
  isUuidV7,
  lp,
  makeToken,
  openBlob,
  openBox,
  openEnvelope,
  openRecord,
  openRekey,
  openWelcome,
  padBinary,
  paddedBlobLength,
  paddedLength,
  parseQrUri,
  parseToken,
  receiveEnvelope,
  sealBlob,
  tokenHash,
  tokenKey,
  toHex,
  u64,
  unpadBinary,
  validateEnvelopeShape,
  verifyPairCandidate,
  verifyToken,
} from '../src/index.ts';

const DIR = join(import.meta.dirname, '..', '..', 'vectors');
type Json = any;
const load = (name: string): Json => JSON.parse(readFileSync(join(DIR, name), 'utf8'));

describe('committed vectors', () => {
  const generated = buildVectors();

  it('match the generator byte for byte (run `npm run vectors` after intended changes)', () => {
    const committed = readdirSync(DIR).filter((f) => f.endsWith('.json')).sort();
    expect(committed).toEqual(Object.keys(generated).sort());
    for (const [name, file] of Object.entries(generated)) {
      expect(readFileSync(join(DIR, name), 'utf8'), name).toBe(serializeVectorFile(file));
    }
  });

  it('every file is self-describing', () => {
    for (const name of Object.keys(generated)) expect(typeof load(name).description).toBe('string');
  });
});

describe('reference implementation against vectors', () => {
  it('b64, lp, u64, device ids', () => {
    const b = load('b64.json');
    for (const c of b.encode) {
      expect(b64encode(fromHex(c.bytes_hex))).toBe(c.b64);
      expect(toHex(b64decode(c.b64))).toBe(c.bytes_hex);
    }
    for (const c of b.decode_invalid) expect(() => b64decode(c.input)).toThrow();
    const e = load('encoding.json');
    for (const c of e.lp) expect(toHex(lp(fromHex(c.input_hex)))).toBe(c.lp_hex);
    for (const c of e.u64) expect(toHex(u64(BigInt(c.n)))).toBe(c.u64_hex);
    const d = load('device_id.json');
    for (const c of d.identities) {
      const id = identityFromSecrets(fromHex(c.sign_sk_hex), fromHex(c.kx_sk_hex));
      expect(id.deviceId).toBe(c.expected_device_id);
      expect(toHex(id.kxPub)).toBe(c.kx_pub_hex);
      expect(deviceIdFromSignPub(fromHex(c.sign_pub_hex))).toBe(c.device_id);
    }
    for (const c of d.invalid_device_ids) expect(isDeviceId(c.value)).toBe(false);
  });

  it('collapse and padding', () => {
    for (const c of load('collapse.json').cases) expect(collapseValue(fromHex(c.group_key_hex), c.from, c.real_key)).toBe(c.collapse);
    const p = load('padding.json');
    for (const c of p.cases) expect(paddedLength(c.input_length)).toBe(c.padded_length);
    for (const c of p.rejected) expect(() => paddedLength(c.input_length)).toThrow();
  });

  it('envelopes', () => {
    for (const c of load('envelope_seal.json').cases) {
      const r = openEnvelope(c.envelope, fromHex(c.sender_sign_pub_hex), new Map([[c.kv, fromHex(c.group_key_hex)]]));
      expect(r.ok && toHex(r.plaintext)).toBe(c.plaintext_hex);
    }
    for (const c of load('envelope_receive.json').cases) {
      const r = receiveEnvelope(c.envelope, contextFromJson(c.context));
      expect(r.ok ? 'ok' : r.error, c.name).toBe(c.expected);
    }
    for (const c of load('envelope_shape.json').cases) {
      const r = validateEnvelopeShape(c.envelope, c.serialized_bytes);
      expect(r.ok ? 'ok' : r.code, c.name).toBe(c.expected);
    }
  });

  it('records', () => {
    for (const c of load('record.json').open_cases) {
      const ctx = c.context;
      const r = openRecord(c.sealed, {
        groupKey: fromHex(ctx.group_key_hex),
        kv: ctx.kv,
        ...(ctx.last_updated !== null ? { lastUpdated: ctx.last_updated } : {}),
      });
      expect(r.ok ? 'ok' : r.error, c.name).toBe(c.expected);
    }
  });

  it('boxes, pairing and rekey', () => {
    for (const c of load('box.json').open_cases) {
      const pt = openBox(c.box, { kxSk: fromHex(c.rcpt_kx_sk_hex), aad: fromHex(c.aad_hex), psk: fromHex(c.psk_hex) });
      expect(pt && toHex(pt), c.name).toBe(c.expected_plaintext_hex);
    }
    const p = load('pairing.json');
    for (const c of p.candidate_cases) {
      const r = verifyPairCandidate(p.pair_id, fromHex(c.pair_secret_hex), c.candidate);
      expect(r.ok ? 'ok' : r.error, c.name).toBe(c.expected);
    }
    for (const c of p.welcome_open_cases) {
      const r = openWelcome(c.box, c.pair_id, c.self_id, fromHex(p.joiner.kx_sk_hex), fromHex(c.pair_secret_hex));
      expect(r.ok ? 'ok' : r.error, c.name).toBe(c.expected);
      if (r.ok) expect(r.welcome).toEqual(p.welcome.welcome);
    }
    for (const c of load('rekey.json').open_cases) {
      const directory = new Map(Object.entries(c.directory as Record<string, string>).map(([id, k]) => [id, b64decode(k)]));
      const r = openRekey(c.version, { selfId: c.self_id, kxSk: fromHex(c.self_kx_sk_hex), directory });
      expect(r.ok ? 'ok' : r.error, c.name).toBe(c.expected);
      if (r.ok) expect(toHex(r.groupKey)).toBe(c.expected_group_key_hex);
    }
  });

  it('tokens, QR URIs and UUIDv7', () => {
    const t = load('token.json');
    const key = tokenKey(t.setup_token);
    for (const c of t.make) {
      expect(makeToken(key, c.device_id, { rand: fromHex(c.rand_hex) })).toBe(c.token);
      expect(toHex(tokenHash(c.token))).toBe(c.token_hash_hex);
    }
    for (const c of t.verify) expect(verifyToken(c.token, tokenKey(c.setup_token)), c.name).toBe(c.expected_device_id);
    for (const c of t.malformed) expect(parseToken(c.token), c.reason).toBeNull();

    const q = load('qr.json');
    for (const c of q.build) expect(parseQrUri(c.uri)?.kind).toBe(c.kind);
    for (const c of q.parse_valid) {
      const r = parseQrUri(c.uri)!;
      const flat = r.kind === 'setup' ? { kind: r.kind, hub: r.hub, setup_token: r.setupToken } : { kind: r.kind, hub: r.hub, pair_id: r.pairId, pair_secret_hex: toHex(r.pairSecret) };
      expect(flat).toEqual(c.expected);
    }
    for (const c of q.parse_invalid) expect(parseQrUri(c.uri), c.reason).toBeNull();

    const u = load('uuidv7.json');
    for (const c of u.format) expect(isUuidV7(c.uuid)).toBe(true);
    for (const c of u.invalid) expect(isUuidV7(c.value)).toBe(false);
  });

  it('blobs', () => {
    const v = load('blob.json');
    for (const c of v.padding.cases) expect(paddedBlobLength(c.plain_length)).toBe(c.padded_length);
    for (const c of v.padding.rejected) expect(() => padBinary(new Uint8Array(c.plain_length))).toThrow(RangeError);
    for (const c of v.pad_examples) {
      expect(toHex(padBinary(fromHex(c.plain_hex))), c.name).toBe(c.padded_hex);
      expect(toHex(unpadBinary(fromHex(c.padded_hex))!), c.name).toBe(c.plain_hex);
    }
    for (const c of v.unpad_invalid) expect(unpadBinary(fromHex(c.padded_hex)), c.name).toBeNull();
    for (const c of v.convergent_key) {
      expect(toHex(convergentKey(fromHex(c.group_key_hex), fromHex(c.plain_hex))), c.name).toBe(c.key_hex);
    }
    for (const c of v.seal) {
      const dims = c.ref.w === undefined ? undefined : { w: c.ref.w, h: c.ref.h };
      const sealed = sealBlob(fromHex(c.plain_hex), c.mime, fromHex(c.key_hex), dims);
      expect(toHex(blobAad(c.mime))).toBe(c.aad_hex);
      expect(toHex(sealed.ct), c.name).toBe(c.ct_hex);
      expect(sealed.ref, c.name).toEqual(c.ref);
      expect(toHex(openBlob(c.ref, fromHex(c.ct_hex))!), c.name).toBe(c.plain_hex);
    }
    for (const c of v.open_cases) {
      const plain = openBlob(c.ref, fromHex(c.ct_hex));
      expect(plain === null ? null : toHex(plain), c.name).toBe(c.plain_hex);
    }
  });
});
