// Thin wrappers over @noble primitives (SPEC §2) so every module uses the same settings.

import { chacha20poly1305 } from '@noble/ciphers/chacha.js';
import { ed25519, x25519 } from '@noble/curves/ed25519.js';
import { hkdf as nobleHkdf } from '@noble/hashes/hkdf.js';
import { hmac } from '@noble/hashes/hmac.js';
import { sha256 as nobleSha256 } from '@noble/hashes/sha2.js';
import { randomBytes } from '@noble/hashes/utils.js';
import { isAllZero, utf8, type Bytes } from './bytes.ts';

/** Source of cryptographically secure random bytes. Injectable for deterministic vectors. */
export type Rng = (length: number) => Bytes;

export const defaultRng: Rng = (length) => randomBytes(length);

export function sha256(data: Bytes): Bytes {
  return nobleSha256(data);
}

export function hmacSha256(key: Bytes, data: Bytes): Bytes {
  return hmac(nobleSha256, key, data);
}

/** HKDF-SHA256 (RFC 5869). An empty salt is equivalent to HashLen zero bytes. */
export function hkdf(ikm: Bytes, salt: Bytes, info: string, length = 32): Bytes {
  return nobleHkdf(nobleSha256, ikm, salt, utf8(info), length);
}

const ZERO_NONCE = new Uint8Array(12);

/** ChaCha20-Poly1305 with the all-zero nonce. Keys MUST be single-use (SPEC §2). */
export function aeadSeal(key: Bytes, aad: Bytes, plaintext: Bytes): Bytes {
  return chacha20poly1305(key, ZERO_NONCE, aad).encrypt(plaintext);
}

/** Returns null when authentication fails. */
export function aeadOpen(key: Bytes, aad: Bytes, ciphertext: Bytes): Bytes | null {
  if (ciphertext.length < 16) return null;
  try {
    return chacha20poly1305(key, ZERO_NONCE, aad).decrypt(ciphertext);
  } catch {
    return null;
  }
}

export function ed25519PublicKey(signSk: Bytes): Bytes {
  return ed25519.getPublicKey(signSk);
}

export function ed25519Sign(signSk: Bytes, message: Bytes): Bytes {
  return ed25519.sign(message, signSk);
}

/** Strict RFC 8032 verification (not ZIP-215), matching CryptoKit. Never throws. */
export function ed25519Verify(signPub: Bytes, message: Bytes, sig: Bytes): boolean {
  if (signPub.length !== 32 || sig.length !== 64) return false;
  try {
    return ed25519.verify(sig, message, signPub, { zip215: false });
  } catch {
    return false;
  }
}

export function x25519PublicKey(kxSk: Bytes): Bytes {
  return x25519.getPublicKey(kxSk);
}

/** X25519 shared secret; null when the peer key is invalid or the result is all-zero. */
export function x25519Shared(kxSk: Bytes, peerPub: Bytes): Bytes | null {
  if (peerPub.length !== 32) return null;
  try {
    const ss = x25519.getSharedSecret(kxSk, peerPub);
    return isAllZero(ss) ? null : ss;
  } catch {
    return null;
  }
}
