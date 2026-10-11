// Encrypted image blobs (SPEC §11). Every blob key encrypts exactly one plaintext, so the
// all-zero nonce is safe: a random key per message image, or a convergent key for icons.

import { b64decodeLength, b64encode } from './b64.ts';
import { concat, lp, u32be, type Bytes } from './bytes.ts';
import { INFO, KEY_LENGTH, LIMITS, TAG } from './constants.ts';
import { aeadOpen, aeadSeal, hkdf, sha256 } from './primitives.ts';

/** A reference to an encrypted blob, carried inside message bodies (SPEC §11.1). */
export interface BlobRef {
  /** b64(SHA-256(ct)), 43 characters. */
  id: string;
  /** b64 of the 32-byte blob key. */
  key: string;
  mime: string;
  /** Plaintext bytes before padding. */
  size: number;
  /** b64(SHA-256(plaintext)[0..16]): the receiver's cache key and integrity check. */
  hash: string;
  w?: number;
  h?: number;
}

const PREFIX_LENGTH = 4;
const SMALL_BUCKET = 256;
const LARGE_BUCKET = 4096;
const INT32_MAX = 2_147_483_647;

const isDimension = (v: unknown): v is number => Number.isInteger(v) && (v as number) >= 1 && (v as number) <= INT32_MAX;

/** Padded size for `n` plaintext bytes: the SPEC §3.1 bucket rule applied to `4 + n`, uncapped. */
export function paddedBlobLength(n: number): number {
  if (!Number.isSafeInteger(n) || n < 0) throw new RangeError('length must be a non-negative integer');
  const length = PREFIX_LENGTH + n;
  const bucket = length <= LARGE_BUCKET ? SMALL_BUCKET : LARGE_BUCKET;
  return Math.ceil(length / bucket) * bucket;
}

/** `u32be(len) || plain || zero bytes`. Throws above LIMITS.maxBlobPlaintext. */
export function padBinary(plain: Uint8Array): Uint8Array {
  if (plain.length > LIMITS.maxBlobPlaintext) throw new RangeError(`blob exceeds ${LIMITS.maxBlobPlaintext} bytes`);
  const out = new Uint8Array(paddedBlobLength(plain.length));
  out.set(u32be(plain.length));
  out.set(plain, PREFIX_LENGTH);
  return out;
}

/** Inverse of padBinary. Null unless the prefix, the padded length and the zero padding are exact. */
export function unpadBinary(padded: Uint8Array): Uint8Array | null {
  if (padded.length < PREFIX_LENGTH) return null;
  const n = new DataView(padded.buffer, padded.byteOffset, padded.byteLength).getUint32(0, false);
  if (n > padded.length - PREFIX_LENGTH || paddedBlobLength(n) !== padded.length) return null;
  for (let i = PREFIX_LENGTH + n; i < padded.length; i++) if (padded[i] !== 0) return null;
  return padded.slice(PREFIX_LENGTH, PREFIX_LENGTH + n);
}

/** `aad = lp("carom/v1/blob") || lp(mime)` */
export function blobAad(mime: string): Bytes {
  return concat(lp(TAG.blob), lp(mime));
}

/** `key = HKDF(ikm=K, salt=SHA-256(plain), info="carom/v1/blob-conv", L=32)`: same icon, same id. */
export function convergentKey(groupKey: Uint8Array, plain: Uint8Array): Uint8Array {
  if (groupKey.length !== KEY_LENGTH) throw new RangeError('group key must be 32 bytes');
  return hkdf(groupKey, sha256(plain), INFO.blobConvergent);
}

/** `id = b64(SHA-256(ct))` */
export function blobId(ct: Uint8Array): string {
  return b64encode(sha256(ct));
}

/** `hash = b64(SHA-256(plain)[0..16])` */
export function blobHash(plain: Uint8Array): string {
  return b64encode(sha256(plain).subarray(0, 16));
}

/** Pads and encrypts one image. `key` must be single-use: random, or convergentKey for icons. */
export function sealBlob(
  plain: Uint8Array,
  mime: string,
  key: Uint8Array,
  dims?: { w: number; h: number },
): { ref: BlobRef; ct: Uint8Array } {
  if (key.length !== KEY_LENGTH) throw new RangeError('blob key must be 32 bytes');
  if (mime.length === 0) throw new RangeError('mime must not be empty');
  if (dims !== undefined && !(isDimension(dims.w) && isDimension(dims.h))) {
    throw new RangeError('w and h must be positive 32-bit integers');
  }
  const ct = aeadSeal(key, blobAad(mime), padBinary(plain));
  const ref: BlobRef = { id: blobId(ct), key: b64encode(key), mime, size: plain.length, hash: blobHash(plain) };
  if (dims !== undefined) {
    ref.w = dims.w;
    ref.h = dims.h;
  }
  return { ref, ct };
}

/** SPEC §11.3: checks the reference, id, tag, padding, size and hash. Null on any failure. */
export function openBlob(ref: BlobRef, ct: Uint8Array): Uint8Array | null {
  if (!isValidBlobRef(ref) || blobId(ct) !== ref.id) return null;
  const key = b64decodeLength(ref.key, KEY_LENGTH);
  if (key === null) return null;
  const padded = aeadOpen(key, blobAad(ref.mime), ct);
  if (padded === null) return null;
  const plain = unpadBinary(padded);
  if (plain === null || plain.length !== ref.size || blobHash(plain) !== ref.hash) return null;
  return plain;
}

/** Structural check of a reference (SPEC §11.1). Unknown fields are ignored. */
export function isValidBlobRef(v: unknown): v is BlobRef {
  if (typeof v !== 'object' || v === null || Array.isArray(v)) return false;
  const r = v as Record<string, unknown>;
  return (
    b64decodeLength(r.id, 32) !== null &&
    b64decodeLength(r.key, KEY_LENGTH) !== null &&
    typeof r.mime === 'string' &&
    r.mime.length > 0 &&
    Number.isSafeInteger(r.size) &&
    (r.size as number) >= 0 &&
    (r.size as number) <= LIMITS.maxBlobPlaintext &&
    b64decodeLength(r.hash, 16) !== null &&
    (r.w === undefined || isDimension(r.w)) &&
    (r.h === undefined || isDimension(r.h))
  );
}
