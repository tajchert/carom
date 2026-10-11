import { describe, expect, it } from 'vitest';
import { b64encode } from '../src/b64.ts';
import {
  blobAad,
  blobHash,
  blobId,
  convergentKey,
  isValidBlobRef,
  openBlob,
  padBinary,
  paddedBlobLength,
  sealBlob,
  unpadBinary,
} from '../src/blob.ts';
import { concat, lp, utf8 } from '../src/bytes.ts';
import { LIMITS } from '../src/constants.ts';
import { aeadSeal, sha256 } from '../src/primitives.ts';

const fill = (n: number, b: number) => new Uint8Array(n).fill(b);
const KEY = fill(32, 7);
const GROUP_KEY = fill(32, 9);

describe('binary padding (SPEC §11.2)', () => {
  it('applies the §3.1 bucket rule to 4 + n, without the payload cap', () => {
    expect(paddedBlobLength(0)).toBe(256);
    expect(paddedBlobLength(252)).toBe(256);
    expect(paddedBlobLength(253)).toBe(512);
    expect(paddedBlobLength(4_092)).toBe(4_096);
    expect(paddedBlobLength(4_093)).toBe(8_192);
    expect(paddedBlobLength(65_533)).toBe(69_632);
    expect(paddedBlobLength(LIMITS.maxBlobPlaintext)).toBe(LIMITS.maxBlobBytes - 4_096);
    expect(() => paddedBlobLength(-1)).toThrow(RangeError);
  });

  it('prefixes the length, pads with zeros and round-trips', () => {
    const plain = utf8('abc');
    const padded = padBinary(plain);
    expect(padded.length).toBe(256);
    expect([...padded.subarray(0, 7)]).toEqual([0, 0, 0, 3, 0x61, 0x62, 0x63]);
    expect(padded.subarray(7).every((b) => b === 0)).toBe(true);
    expect(unpadBinary(padded)).toEqual(plain);
    expect(unpadBinary(padBinary(new Uint8Array(0)))).toEqual(new Uint8Array(0));
    expect(() => padBinary(new Uint8Array(LIMITS.maxBlobPlaintext + 1))).toThrow(RangeError);
  });

  it('rejects inexact padding', () => {
    const padded = padBinary(utf8('abc'));
    expect(unpadBinary(padded.subarray(0, 3))).toBeNull();
    const nonZero = padded.slice();
    nonZero[255] = 1;
    expect(unpadBinary(nonZero)).toBeNull();
    const longPrefix = padded.slice();
    longPrefix[3] = 253;
    expect(unpadBinary(longPrefix)).toBeNull();
    expect(unpadBinary(concat(padded, new Uint8Array(256)))).toBeNull();
  });
});

describe('blob keys, seal and open (SPEC §11.2, §11.3)', () => {
  it('derives the convergent key from the group key and the plaintext', () => {
    const icon = fill(300, 1);
    expect(convergentKey(GROUP_KEY, icon)).toEqual(convergentKey(GROUP_KEY, icon.slice()));
    expect(convergentKey(GROUP_KEY, icon)).not.toEqual(convergentKey(fill(32, 10), icon));
    expect(convergentKey(GROUP_KEY, icon)).not.toEqual(convergentKey(GROUP_KEY, fill(300, 2)));
    expect(() => convergentKey(fill(31, 0), icon)).toThrow(RangeError);
    // Same icon, same key, same id: uploaded once per key version.
    const a = sealBlob(icon, 'image/webp', convergentKey(GROUP_KEY, icon));
    const b = sealBlob(icon, 'image/webp', convergentKey(GROUP_KEY, icon));
    expect(a.ref.id).toBe(b.ref.id);
  });

  it('seals into a reference that opens to the plaintext', () => {
    const plain = fill(1_000, 3);
    const { ref, ct } = sealBlob(plain, 'image/webp', KEY, { w: 192, h: 192 });
    expect(ref).toEqual({
      id: b64encode(sha256(ct)),
      key: b64encode(KEY),
      mime: 'image/webp',
      size: 1_000,
      hash: b64encode(sha256(plain).subarray(0, 16)),
      w: 192,
      h: 192,
    });
    expect(ct.length).toBe(1_024 + 16);
    expect(blobId(ct)).toBe(ref.id);
    expect(blobHash(plain)).toBe(ref.hash);
    expect(blobAad('image/webp')).toEqual(concat(lp('carom/v1/blob'), lp('image/webp')));
    expect(openBlob(ref, ct)).toEqual(plain);
    expect(sealBlob(plain, 'image/webp', KEY).ref).not.toHaveProperty('w');
  });

  it('refuses bad inputs when sealing', () => {
    expect(() => sealBlob(fill(1, 0), 'image/webp', fill(31, 0))).toThrow(RangeError);
    expect(() => sealBlob(fill(1, 0), '', KEY)).toThrow(RangeError);
    expect(() => sealBlob(fill(1, 0), 'image/webp', KEY, { w: 0, h: 1 })).toThrow(RangeError);
  });

  it('opens only an exact, untampered blob', () => {
    const plain = fill(100, 4);
    const { ref, ct } = sealBlob(plain, 'image/png', KEY);
    const flipped = ct.slice();
    flipped[0]! ^= 1;
    expect(openBlob(ref, flipped)).toBeNull();
    expect(openBlob({ ...ref, id: blobId(flipped) }, flipped)).toBeNull();
    expect(openBlob({ ...ref, mime: 'image/webp' }, ct)).toBeNull();
    expect(openBlob({ ...ref, key: b64encode(fill(32, 8)) }, ct)).toBeNull();
    expect(openBlob({ ...ref, size: 101 }, ct)).toBeNull();
    expect(openBlob({ ...ref, hash: blobHash(fill(100, 5)) }, ct)).toBeNull();
    // A valid tag over inexact padding is still rejected.
    const badPadding = padBinary(plain);
    badPadding[200] = 1;
    const forged = aeadSeal(KEY, blobAad('image/png'), badPadding);
    expect(openBlob({ ...ref, id: blobId(forged) }, forged)).toBeNull();
  });

  it('checks reference shapes', () => {
    const { ref } = sealBlob(fill(10, 6), 'image/webp', KEY, { w: 96, h: 96 });
    expect(isValidBlobRef(ref)).toBe(true);
    expect(isValidBlobRef({ ...ref, extra: 1 })).toBe(true);
    const bad: unknown[] = [
      null,
      'x',
      { ...ref, id: ref.id.slice(1) },
      { ...ref, key: ref.key + '=' },
      { ...ref, mime: '' },
      { ...ref, size: -1 },
      { ...ref, size: 1.5 },
      { ...ref, size: LIMITS.maxBlobPlaintext + 1 },
      { ...ref, hash: ref.id },
      { ...ref, w: 0 },
      { ...ref, h: '96' },
    ];
    for (const v of bad) expect(isValidBlobRef(v), JSON.stringify(v)).toBe(false);
    const { hash: _, ...noHash } = ref;
    expect(isValidBlobRef(noHash)).toBe(false);
  });
});
