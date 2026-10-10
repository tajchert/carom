// HKDF-derived keys (SPEC §2.1, §2.4, §6, §8).

import { concat, utf8, type Bytes } from './bytes.ts';
import { INFO, KEY_LENGTH, SALT_LENGTH } from './constants.ts';
import { hkdf } from './primitives.ts';

const EMPTY = new Uint8Array(0);

function requireLength(b: Bytes, n: number, name: string): void {
  if (b.length !== n) throw new RangeError(`${name} must be ${n} bytes`);
}

/** `msg_key(K, salt) = HKDF(ikm=K, salt=salt, info="carom/v1/msg", L=32)` */
export function msgKey(groupKey: Bytes, salt: Bytes): Bytes {
  requireLength(groupKey, KEY_LENGTH, 'group key');
  requireLength(salt, SALT_LENGTH, 'salt');
  return hkdf(groupKey, salt, INFO.msg);
}

/** `K_collapse(K) = HKDF(ikm=K, salt="", info="carom/v1/collapse", L=32)` */
export function collapseKey(groupKey: Bytes): Bytes {
  requireLength(groupKey, KEY_LENGTH, 'group key');
  return hkdf(groupKey, EMPTY, INFO.collapse);
}

/** `k_pair = HKDF(ikm=pair_secret, salt="", info="carom/v1/pair", L=32)` */
export function pairKey(pairSecret: Bytes): Bytes {
  requireLength(pairSecret, KEY_LENGTH, 'pair secret');
  return hkdf(pairSecret, EMPTY, INFO.pair);
}

/** `token_key = HKDF(ikm=UTF8(SETUP_TOKEN), salt="", info="carom/v1/token-key", L=32)` */
export function tokenKey(setupToken: string): Bytes {
  return hkdf(utf8(setupToken), EMPTY, INFO.tokenKey);
}

/** `key = HKDF(ikm=ss || psk, salt=eph_pub || rcpt_kx_pub, info="carom/v1/box", L=32)` */
export function boxKey(sharedSecret: Bytes, psk: Bytes, ephPub: Bytes, rcptKxPub: Bytes): Bytes {
  return hkdf(concat(sharedSecret, psk), concat(ephPub, rcptKxPub), INFO.box);
}
