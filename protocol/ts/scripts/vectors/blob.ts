// Blob vectors (SPEC §11): binary padding, convergent keys, byte-exact seals and open cases.
// The sealed fixtures here are also the blob references inside messages.json and envelope_seal.json.

import { b64decode } from '../../src/b64.ts';
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
  type BlobRef,
} from '../../src/blob.ts';
import { concat, u32be, utf8, type Bytes } from '../../src/bytes.ts';
import { INFO, LIMITS } from '../../src/constants.ts';
import { aeadOpen, aeadSeal, sha256 } from '../../src/primitives.ts';
import { b64, hex, K1, K2, seed, seededRng, type VectorFile } from './fixtures.ts';

interface BlobSpec {
  name: string;
  keyKind: 'random' | 'convergent';
  plain: Bytes;
  mime: string;
  key: Bytes;
  dims?: { w: number; h: number };
}

const ICON_PLAIN = seededRng('blob/plain/icon')(300);
const SMALL_ICON_PLAIN = seededRng('blob/plain/small-icon')(120);
const AVATAR_PLAIN = seededRng('blob/plain/avatar')(1_000);
const PHOTO_PLAIN = seededRng('blob/plain/photo')(5_000);

const AVATAR: BlobSpec = {
  name: 'random key, avatar (webp, 192x192)',
  keyKind: 'random',
  plain: AVATAR_PLAIN,
  mime: 'image/webp',
  key: seed('blob/key/avatar'),
  dims: { w: 192, h: 192 },
};
const PHOTO: BlobSpec = {
  name: 'random key, photo past the 4096-byte bucket (jpeg, 1080x720)',
  keyKind: 'random',
  plain: PHOTO_PLAIN,
  mime: 'image/jpeg',
  key: seed('blob/key/photo'),
  dims: { w: 1_080, h: 720 },
};
const ICON: BlobSpec = {
  name: 'convergent key under kv 1, app icon (webp, 128x128)',
  keyKind: 'convergent',
  plain: ICON_PLAIN,
  mime: 'image/webp',
  key: convergentKey(K1, ICON_PLAIN),
  dims: { w: 128, h: 128 },
};
const SMALL_ICON: BlobSpec = {
  name: 'convergent key under kv 1, small icon (webp, 96x96)',
  keyKind: 'convergent',
  plain: SMALL_ICON_PLAIN,
  mime: 'image/webp',
  key: convergentKey(K1, SMALL_ICON_PLAIN),
  dims: { w: 96, h: 96 },
};
const EMPTY: BlobSpec = {
  name: 'random key, empty plaintext, no dimensions',
  keyKind: 'random',
  plain: new Uint8Array(0),
  mime: 'image/png',
  key: seed('blob/key/empty'),
};

const sealSpec = (s: BlobSpec) => sealBlob(s.plain, s.mime, s.key, s.dims);

export const AVATAR_REF = sealSpec(AVATAR).ref;
export const PHOTO_REF = sealSpec(PHOTO).ref;
export const ICON_REF = sealSpec(ICON).ref;
export const SMALL_ICON_REF = sealSpec(SMALL_ICON).ref;

/** The first failing SPEC §11.3 check, or "ok". */
function openCode(ref: BlobRef, ct: Bytes): string {
  if (!isValidBlobRef(ref)) return 'bad_ref';
  if (blobId(ct) !== ref.id) return 'bad_id';
  const padded = aeadOpen(b64decode(ref.key), blobAad(ref.mime), ct);
  if (padded === null) return 'decrypt_failed';
  const plain = unpadBinary(padded);
  if (plain === null) return 'bad_padding';
  if (plain.length !== ref.size) return 'bad_size';
  return blobHash(plain) === ref.hash ? 'ok' : 'bad_hash';
}

function withByte(b: Bytes, i: number, v: number): Bytes {
  const c = b.slice();
  c[i] = v;
  return c;
}

export function blobVectors(): VectorFile {
  const paddingLengths = [0, 1, 252, 253, 4_092, 4_093, 65_532, 65_533, LIMITS.maxBlobPlaintext];
  const padExamples: Array<[string, Bytes]> = [
    ['empty', new Uint8Array(0)],
    ['three bytes "abc"', utf8('abc')],
    ['252 bytes fill one 256-byte bucket exactly', seededRng('blob/pad/252')(252)],
    ['253 bytes need a second bucket', seededRng('blob/pad/253')(253)],
  ];
  const abc = padBinary(utf8('abc'));
  const unpadInvalid: Array<[string, Bytes]> = [
    ['empty input', new Uint8Array(0)],
    ['shorter than the 4-byte prefix', abc.subarray(0, 3)],
    ['non-zero padding byte', withByte(abc, 255, 1)],
    ['prefix larger than the data', withByte(abc, 3, 253)],
    ['prefix 0xffffffff', concat(u32be(0xffff_ffff), abc.subarray(4))],
    ['padded length is not the bucket size for the prefix', concat(abc, new Uint8Array(256))],
  ];
  for (const [name, p] of unpadInvalid) if (unpadBinary(p) !== null) throw new Error(`unpad accepted "${name}"`);

  const convergent: Array<[string, Bytes, Bytes]> = [
    ['app icon under kv 1', K1, ICON_PLAIN],
    ['small icon under kv 1', K1, SMALL_ICON_PLAIN],
    ['the same app icon under kv 2 gives another key', K2, ICON_PLAIN],
    ['empty plaintext under kv 1', K1, new Uint8Array(0)],
  ];

  const tinyPlain = utf8('carom blob test vector');
  const tinyKey = seed('blob/key/tiny');
  const tiny = sealBlob(tinyPlain, 'image/png', tinyKey);
  const flipped = tiny.ct.slice();
  flipped[flipped.length - 1]! ^= 0x01;
  const sealPadded = (padded: Bytes): { ref: BlobRef; ct: Bytes } => {
    const ct = aeadSeal(tinyKey, blobAad('image/png'), padded);
    return { ref: { ...tiny.ref, id: blobId(ct) }, ct };
  };
  const openCases: Array<{ name: string; ref: BlobRef; ct: Bytes; expected: string }> = [
    { name: 'valid', ref: tiny.ref, ct: tiny.ct, expected: 'ok' },
    { name: 'key is 31 bytes', ref: { ...tiny.ref, key: b64(tinyKey.subarray(0, 31)) }, ct: tiny.ct, expected: 'bad_ref' },
    { name: 'hash is 15 bytes', ref: { ...tiny.ref, hash: b64(sha256(tinyPlain).subarray(0, 15)) }, ct: tiny.ct, expected: 'bad_ref' },
    { name: 'last ciphertext byte flipped, id unchanged', ref: tiny.ref, ct: flipped, expected: 'bad_id' },
    { name: 'last ciphertext byte flipped, id recomputed', ref: { ...tiny.ref, id: blobId(flipped) }, ct: flipped, expected: 'decrypt_failed' },
    { name: 'mime changed (AAD mismatch)', ref: { ...tiny.ref, mime: 'image/webp' }, ct: tiny.ct, expected: 'decrypt_failed' },
    { name: 'wrong key', ref: { ...tiny.ref, key: b64(seed('blob/key/other')) }, ct: tiny.ct, expected: 'decrypt_failed' },
    { name: 'non-zero padding under a valid tag', ...sealPadded(withByte(padBinary(tinyPlain), 255, 1)), expected: 'bad_padding' },
    {
      name: 'length prefix past the data under a valid tag',
      ...sealPadded(concat(u32be(0xffff_ffff), padBinary(tinyPlain).subarray(4))),
      expected: 'bad_padding',
    },
    { name: 'size is one more than the plaintext', ref: { ...tiny.ref, size: tiny.ref.size + 1 }, ct: tiny.ct, expected: 'bad_size' },
    { name: 'hash of another plaintext', ref: { ...tiny.ref, hash: blobHash(utf8('another plaintext')) }, ct: tiny.ct, expected: 'bad_hash' },
  ];
  for (const c of openCases) {
    const code = openCode(c.ref, c.ct);
    if (code !== c.expected) throw new Error(`blob open vector "${c.name}": expected ${c.expected}, got ${code}`);
    if ((openBlob(c.ref, c.ct) === null) !== (code !== 'ok')) throw new Error(`openBlob disagrees on "${c.name}"`);
  }

  return {
    description:
      'Encrypted blobs (SPEC §11). padded = u32be(len(P)) || P || zero bytes, where the padded length is the §3.1 bucket rule applied to 4 + len(P) (multiples of 256 up to 4096, then multiples of 4096, no 65536 cap). P is at most 1044476 bytes so that ct stays within 1048576. aad = lp("carom/v1/blob") || lp(mime); ct = ChaCha20-Poly1305(key, nonce = 12 zero bytes, aad, padded); id = b64(SHA-256(ct)); hash = b64(SHA-256(P)[0..16]). Convergent keys (app and small icons): key = HKDF-SHA256(ikm = group key, salt = SHA-256(P), info = "carom/v1/blob-conv", L = 32). seal cases are byte-exact and ref is the exact reference JSON. open_cases are checked in order: reference shape (bad_ref), b64(SHA-256(ct)) == id (bad_id), decryption (decrypt_failed), exact padding (bad_padding), plaintext length == size (bad_size), hash (bad_hash). expected is "ok" or the first failing code; plain_hex is the plaintext for "ok" and null otherwise.',
    padding: {
      cases: paddingLengths.map((n) => ({ plain_length: n, padded_length: paddedBlobLength(n) })),
      rejected: [{ plain_length: LIMITS.maxBlobPlaintext + 1, reason: 'the ciphertext would exceed 1048576 bytes' }],
    },
    pad_examples: padExamples.map(([name, p]) => ({ name, plain_hex: hex(p), padded_hex: hex(padBinary(p)) })),
    unpad_invalid: unpadInvalid.map(([name, p]) => ({ name, padded_hex: hex(p) })),
    convergent_key: convergent.map(([name, k, p]) => ({
      name,
      group_key_hex: hex(k),
      plain_hex: hex(p),
      salt_hex: hex(sha256(p)),
      info: INFO.blobConvergent,
      key_hex: hex(convergentKey(k, p)),
    })),
    seal: [AVATAR, PHOTO, ICON, SMALL_ICON, EMPTY].map((s) => {
      const { ref, ct } = sealSpec(s);
      return {
        name: s.name,
        key_kind: s.keyKind,
        ...(s.keyKind === 'convergent' ? { group_key_hex: hex(K1) } : {}),
        key_hex: hex(s.key),
        mime: s.mime,
        plain_length: s.plain.length,
        plain_hex: hex(s.plain),
        padded_length: paddedBlobLength(s.plain.length),
        aad_hex: hex(blobAad(s.mime)),
        ct_hex: hex(ct),
        ref,
      };
    }),
    open_cases: openCases.map((c) => ({
      name: c.name,
      ref: c.ref,
      ct_hex: hex(c.ct),
      expected: c.expected,
      plain_hex: c.expected === 'ok' ? hex(tinyPlain) : null,
    })),
  };
}
