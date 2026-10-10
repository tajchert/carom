import { describe, expect, it } from 'vitest';
import { collapseValue } from '../src/collapse.ts';
import { deviceIdFromSignPub, deviceIdMatches, generateIdentity, identityFromSecrets, isDeviceId, sortIds } from '../src/device.ts';
import { boxKey, collapseKey, msgKey, pairKey, tokenKey } from '../src/kdf.ts';
import { aeadOpen, aeadSeal, defaultRng, ed25519Sign, ed25519Verify, x25519Shared } from '../src/primitives.ts';
import { decryptSeal, isValidKv, seal, sealAad, verifySeal } from '../src/seal.ts';
import { fromHex, toHex } from '../src/bytes.ts';

const fixedRng = (byte: number) => (n: number) => new Uint8Array(n).fill(byte);
const K = new Uint8Array(32).fill(7);

describe('kdf', () => {
  it('requires 32-byte keys and salts', () => {
    expect(() => msgKey(new Uint8Array(31), new Uint8Array(32))).toThrow(RangeError);
    expect(() => msgKey(K, new Uint8Array(16))).toThrow(RangeError);
    expect(() => collapseKey(new Uint8Array(16))).toThrow(RangeError);
    expect(() => pairKey(new Uint8Array(0))).toThrow(RangeError);
  });

  it('derives distinct 32-byte keys per purpose', () => {
    const keys = [msgKey(K, K), collapseKey(K), pairKey(K), tokenKey('x'.repeat(32)), boxKey(K, new Uint8Array(0), K, K)].map(toHex);
    expect(new Set(keys).size).toBe(keys.length);
    for (const k of keys) expect(k.length).toBe(64);
  });

  it('collapse depends on K, from and real key', () => {
    const a = collapseValue(K, 'dev_a', 'clip');
    expect(a).toHaveLength(22);
    expect(collapseValue(K, 'dev_b', 'clip')).not.toBe(a);
    expect(collapseValue(K, 'dev_a', 'status')).not.toBe(a);
    expect(collapseValue(new Uint8Array(32), 'dev_a', 'clip')).not.toBe(a);
  });
});

describe('primitives', () => {
  it('aeadOpen rejects short or tampered ciphertexts', () => {
    const ct = aeadSeal(K, new Uint8Array(0), new Uint8Array([1, 2, 3]));
    expect(aeadOpen(K, new Uint8Array(0), ct)).toEqual(new Uint8Array([1, 2, 3]));
    expect(aeadOpen(K, new Uint8Array(0), ct.subarray(0, 15))).toBeNull();
    expect(aeadOpen(K, new Uint8Array([1]), ct)).toBeNull();
  });

  it('ed25519Verify never throws on malformed input', () => {
    const id = generateIdentity(fixedRng(1));
    const sig = ed25519Sign(id.signSk, new Uint8Array([1]));
    expect(ed25519Verify(id.signPub, new Uint8Array([1]), sig)).toBe(true);
    expect(ed25519Verify(id.signPub.subarray(0, 31), new Uint8Array([1]), sig)).toBe(false);
    expect(ed25519Verify(id.signPub, new Uint8Array([1]), sig.subarray(0, 63))).toBe(false);
    expect(ed25519Verify(new Uint8Array(32).fill(0xff), new Uint8Array([1]), sig)).toBe(false);
  });

  it('x25519Shared rejects low-order points and bad lengths', () => {
    const id = generateIdentity(fixedRng(2));
    expect(x25519Shared(id.kxSk, new Uint8Array(32))).toBeNull();
    expect(x25519Shared(id.kxSk, new Uint8Array(31))).toBeNull();
    expect(x25519Shared(id.kxSk, generateIdentity(fixedRng(3)).kxPub)).toHaveLength(32);
  });

  it('defaultRng returns fresh bytes', () => {
    expect(toHex(defaultRng(32))).not.toBe(toHex(defaultRng(32)));
  });
});

describe('device identity', () => {
  it('generateIdentity uses the injected rng and derives the id', () => {
    const id = generateIdentity(fixedRng(9));
    expect(id.signSk).toEqual(new Uint8Array(32).fill(9));
    expect(id.deviceId).toBe(deviceIdFromSignPub(id.signPub));
    expect(isDeviceId(id.deviceId)).toBe(true);
    expect(deviceIdMatches(id.deviceId, id.signPub)).toBe(true);
    expect(deviceIdMatches(id.deviceId, generateIdentity(fixedRng(8)).signPub)).toBe(false);
    expect(deviceIdMatches(id.deviceId, new Uint8Array(31))).toBe(false);
  });

  it('validates key lengths', () => {
    expect(() => identityFromSecrets(new Uint8Array(31), new Uint8Array(32))).toThrow(RangeError);
    expect(() => deviceIdFromSignPub(new Uint8Array(33))).toThrow(RangeError);
    expect(isDeviceId(5)).toBe(false);
  });

  it('sortIds orders by byte value', () => {
    expect(sortIds(['dev_b', 'dev_B', 'dev_-', 'dev__', 'dev_a'])).toEqual(['dev_-', 'dev_B', 'dev__', 'dev_a', 'dev_b']);
  });
});

describe('seal', () => {
  const id = generateIdentity(fixedRng(4));
  const input = { tag: 't', header: [fromHex('01')], plaintext: fromHex('abcd'), groupKey: K, kv: 1, signSk: id.signSk, salt: new Uint8Array(32) };

  it('round-trips and binds header, kv and salt', () => {
    const { aad, ct, sig } = seal(input);
    expect(toHex(aad)).toBe(toHex(sealAad('t', [fromHex('01')], 1, new Uint8Array(32))));
    expect(verifySeal(aad, ct, sig, id.signPub)).toBe(true);
    expect(decryptSeal(aad, ct, K, new Uint8Array(32))).toEqual(fromHex('abcd'));
    expect(verifySeal(sealAad('t', [fromHex('02')], 1, new Uint8Array(32)), ct, sig, id.signPub)).toBe(false);
    expect(decryptSeal(sealAad('t', [fromHex('01')], 2, new Uint8Array(32)), ct, K, new Uint8Array(32))).toBeNull();
  });

  it('rejects invalid kv, salt or key', () => {
    expect(() => seal({ ...input, kv: 0 })).toThrow(RangeError);
    expect(() => seal({ ...input, salt: new Uint8Array(31) })).toThrow(RangeError);
    expect(() => seal({ ...input, groupKey: new Uint8Array(16) })).toThrow(RangeError);
    expect(isValidKv(2 ** 32 - 1)).toBe(true);
    expect(isValidKv(2 ** 32)).toBe(false);
    expect(isValidKv('1')).toBe(false);
  });
});
