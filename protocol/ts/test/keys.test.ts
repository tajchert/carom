import { describe, expect, it } from 'vitest';
import { b64encode } from '../src/b64.ts';
import { openBox, parseBox, sealBox } from '../src/box.ts';
import { utf8 } from '../src/bytes.ts';
import { generateIdentity } from '../src/device.ts';
import {
  buildPairAccept,
  isValidWelcome,
  openWelcome,
  pairRequestMac,
  sealWelcome,
  signPairPop,
  verifyPairCandidate,
  verifyPairPop,
  welcomeAad,
} from '../src/pairing.ts';
import { ed25519Sign } from '../src/primitives.ts';
import { pairKey } from '../src/kdf.ts';
import { isSealedRecordShape, openRecord, sealRecord, type DeviceRecord } from '../src/record.ts';
import { buildRekey, isKeyringVersionShape, openRekey, rekeyBoxAad, rekeySigInput, verifyRekeySignature } from '../src/rekey.ts';
import { defaultReceive } from '../src/routing.ts';

const fill = (b: number) => (n: number) => new Uint8Array(n).fill(b);
const A = generateIdentity(fill(1));
const B = generateIdentity(fill(2));
const C = generateIdentity(fill(3));
const K1 = new Uint8Array(32).fill(0x11);
const K2 = new Uint8Array(32).fill(0x22);

function recordOf(id = A, updated = 1000): DeviceRecord {
  return {
    id: id.deviceId,
    name: 'Mac',
    kind: 'desktop',
    platform: 'macos',
    app_version: '1.0.0',
    sign_pub: b64encode(id.signPub),
    kx_pub: b64encode(id.kxPub),
    caps: ['clipboard.set@1'],
    receive: defaultReceive('desktop') as DeviceRecord['receive'],
    updated,
  };
}

describe('records', () => {
  it('seal and open with the current key, honouring rollback', () => {
    const sealed = sealRecord({ record: recordOf(), groupKey: K2, kv: 2, signSk: A.signSk, rng: fill(9) });
    expect(Object.keys(sealed)).toEqual(['id', 'updated', 'kv', 'salt', 'ct', 'sig']);
    expect(openRecord(sealed, { groupKey: K2, kv: 2 })).toEqual({ ok: true, record: recordOf() });
    expect(openRecord(sealed, { groupKey: K2, kv: 2, lastUpdated: 1001 })).toEqual({ ok: false, error: 'rollback' });
    expect(openRecord(sealed, { groupKey: K1, kv: 3 })).toEqual({ ok: false, error: 'old_kv' });
    expect(openRecord(sealed, { groupKey: K1, kv: 1 })).toEqual({ ok: false, error: 'unknown_kv' });
  });

  it('refuses to seal invalid or foreign records', () => {
    expect(() => sealRecord({ record: { ...recordOf(), kind: 'tv' as 'cli' }, groupKey: K1, kv: 1, signSk: A.signSk })).toThrow(RangeError);
    expect(() => sealRecord({ record: recordOf(), groupKey: K1, kv: 1, signSk: B.signSk })).toThrow(/do not match/);
    expect(() => sealRecord({ record: { ...recordOf(), id: B.deviceId }, groupKey: K1, kv: 1, signSk: A.signSk })).toThrow(/do not match/);
  });

  it('isSealedRecordShape is strict', () => {
    const sealed = sealRecord({ record: recordOf(), groupKey: K1, kv: 1, signSk: A.signSk });
    expect(isSealedRecordShape(sealed)).toBe(true);
    expect(isSealedRecordShape({ ...sealed, ct: 'AA' })).toBe(false);
    expect(isSealedRecordShape({ ...sealed, ct: '!' })).toBe(false);
    expect(isSealedRecordShape({ ...sealed, updated: -1 })).toBe(false);
    expect(isSealedRecordShape(null)).toBe(false);
    const { sig: _, ...noSig } = sealed;
    expect(isSealedRecordShape(noSig)).toBe(false);
  });
});

describe('box', () => {
  const aad = utf8('aad');
  it('round-trips with and without psk using a random ephemeral key', () => {
    const box = sealBox({ rcptKxPub: B.kxPub, aad, plaintext: utf8('hi') });
    expect(openBox(box, { kxSk: B.kxSk, aad })).toEqual(utf8('hi'));
    const withPsk = sealBox({ rcptKxPub: B.kxPub, aad, plaintext: utf8('hi'), psk: K1, rng: fill(4) });
    expect(openBox(withPsk, { kxSk: B.kxSk, aad, psk: K1 })).toEqual(utf8('hi'));
    expect(openBox(withPsk, { kxSk: B.kxSk, aad })).toBeNull();
  });

  it('rejects a low-order recipient key when sealing', () => {
    expect(() => sealBox({ rcptKxPub: new Uint8Array(32), aad, plaintext: utf8('x') })).toThrow(RangeError);
  });

  it.each([null, [], { epk: 'x' }, { epk: b64encode(A.kxPub), ct: 'AA' }, { epk: b64encode(A.kxPub), ct: '!!!!' }, { epk: b64encode(A.kxPub), ct: b64encode(new Uint8Array(16)), x: 1 }])(
    'parseBox rejects %j',
    (v) => {
      expect(parseBox(v)).toBeNull();
    },
  );
});

describe('pairing', () => {
  const secret = new Uint8Array(32).fill(0x33);
  const pairId = 'p_1';

  it('accept body verifies on the inviter and at the Hub', () => {
    const body = buildPairAccept(pairId, secret, B);
    const r = verifyPairCandidate(pairId, secret, { device_id: B.deviceId, ...body });
    expect(r).toMatchObject({ ok: true, deviceId: B.deviceId });
    expect(verifyPairPop(pairId, B.signPub, B.kxPub, signPairPop(B.signSk, pairId, B.signPub, B.kxPub))).toBe(true);
    expect(verifyPairPop('p_2', B.signPub, B.kxPub, signPairPop(B.signSk, pairId, B.signPub, B.kxPub))).toBe(false);
    expect(pairRequestMac(pairKey(secret), pairId, B.signPub, B.kxPub)).toHaveLength(32);
  });

  it.each([null, 'x', { device_id: 1 }])('rejects malformed candidates: %j', (c) => {
    expect(verifyPairCandidate(pairId, secret, c)).toEqual({ ok: false, error: 'bad_shape' });
  });

  it('welcome round-trip and validation', () => {
    const welcome = { keys: [{ kv: 1, k: b64encode(K1) }], inviter: A.deviceId };
    const box = sealWelcome({ pairId, candidateId: B.deviceId, candidateKxPub: B.kxPub, pairSecret: secret, welcome, rng: fill(5) });
    expect(openWelcome(box, pairId, B.deviceId, B.kxSk, secret)).toEqual({ ok: true, welcome });
    expect(openWelcome(box, pairId, B.deviceId, C.kxSk, secret)).toEqual({ ok: false, error: 'decrypt_failed' });
    expect(() => sealWelcome({ pairId, candidateId: B.deviceId, candidateKxPub: B.kxPub, pairSecret: secret, welcome: { keys: [], inviter: A.deviceId } })).toThrow();
    expect(isValidWelcome({ keys: [{ kv: 1, k: b64encode(K1) }, { kv: 1, k: b64encode(K2) }], inviter: A.deviceId })).toBe(false);
    expect(isValidWelcome({ keys: [null], inviter: A.deviceId })).toBe(false);
    expect(isValidWelcome([])).toBe(false);
  });

  it('openWelcome reports a box that decrypts to something else', () => {
    const box = sealBox({
      rcptKxPub: B.kxPub,
      aad: welcomeAad(pairId, B.deviceId),
      plaintext: utf8('{"keys":[]}'),
      psk: pairKey(secret),
    });
    expect(openWelcome(box, pairId, B.deviceId, B.kxSk, secret)).toEqual({ ok: false, error: 'bad_welcome' });
  });
});

describe('rekey', () => {
  const members = [A, B].map((m) => ({ id: m.deviceId, kxPub: m.kxPub }));
  const directory = new Map([
    [A.deviceId, A.signPub],
    [B.deviceId, B.signPub],
  ]);

  it('builds, verifies and opens with random ephemeral keys', () => {
    const v = buildRekey({ by: A, kv: 2, groupKey: K2, members });
    expect(isKeyringVersionShape(v)).toBe(true);
    expect(verifyRekeySignature(v, A.signPub)).toBe(true);
    expect(verifyRekeySignature({ ...v, sig: 'x' }, A.signPub)).toBe(false);
    expect(openRekey(v, { selfId: B.deviceId, kxSk: B.kxSk, directory })).toEqual({ ok: true, kv: 2, groupKey: K2 });
  });

  it('validates its inputs', () => {
    expect(() => buildRekey({ by: A, kv: 1, groupKey: K2, members })).toThrow(RangeError);
    expect(() => buildRekey({ by: A, kv: 2, groupKey: K2.subarray(1), members })).toThrow(RangeError);
    expect(() => buildRekey({ by: C, kv: 2, groupKey: K2, members })).toThrow(/include the rekeying device/);
    expect(() => buildRekey({ by: A, kv: 2, groupKey: K2, members: [...members, members[0]!] })).toThrow(/duplicate/);
    expect(() => rekeySigInput(A.deviceId, 2, { [A.deviceId]: { epk: 'x', ct: 'y' } })).toThrow(RangeError);
  });

  it('reports a validly signed box that does not hold a 32-byte key, and a missing box', () => {
    const boxes = {
      [A.deviceId]: sealBox({ rcptKxPub: A.kxPub, aad: rekeyBoxAad(A.deviceId, A.deviceId, 2), plaintext: K2 }),
      [B.deviceId]: sealBox({ rcptKxPub: B.kxPub, aad: rekeyBoxAad(A.deviceId, B.deviceId, 2), plaintext: new Uint8Array(16) }),
    };
    const sig = b64encode(ed25519Sign(A.signSk, rekeySigInput(A.deviceId, 2, boxes)));
    expect(openRekey({ kv: 2, by: A.deviceId, boxes, sig }, { selfId: B.deviceId, kxSk: B.kxSk, directory })).toEqual({ ok: false, error: 'bad_key' });
    const signed = buildRekey({ by: A, kv: 2, groupKey: K2, members: [members[0]!] });
    expect(openRekey(signed, { selfId: B.deviceId, kxSk: B.kxSk, directory })).toEqual({ ok: false, error: 'no_box' });
  });
});
