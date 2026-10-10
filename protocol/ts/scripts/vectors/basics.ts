// Vectors for encodings, device ids, key derivation, collapse values and padding.

import { b64decode } from '../../src/b64.ts';
import { concat, lp, u64, utf8, type Bytes } from '../../src/bytes.ts';
import { collapseInput, collapseValue } from '../../src/collapse.ts';
import { deviceIdFromSignPub, isDeviceId } from '../../src/device.ts';
import { boxKey, collapseKey, msgKey, pairKey, tokenKey } from '../../src/kdf.ts';
import { paddedLength } from '../../src/payload.ts';
import { hmacSha256, sha256, x25519PublicKey, x25519Shared } from '../../src/primitives.ts';
import { b64, hex, identityJson, K1, K2, LAPTOP, MAC, OUTSIDER, PHONE, seed, TABLET, type VectorFile } from './fixtures.ts';

function bytesOf(...values: number[]): Bytes {
  return Uint8Array.from(values);
}

function decodeRejects(input: string): boolean {
  try {
    b64decode(input);
    return false;
  } catch {
    return true;
  }
}

export function b64Vectors(): VectorFile {
  const encode: Bytes[] = [
    bytesOf(),
    bytesOf(0x66),
    bytesOf(0x66, 0x6f),
    bytesOf(0x66, 0x6f, 0x6f),
    bytesOf(0x66, 0x6f, 0x6f, 0x62),
    bytesOf(0x66, 0x6f, 0x6f, 0x62, 0x61),
    bytesOf(0xfb, 0xff),
    bytesOf(0xfb, 0xef, 0xbe),
    bytesOf(0xff, 0xff, 0xff, 0xff),
    seed('b64/16').subarray(0, 16),
    seed('b64/32'),
  ];
  const invalid: Array<[string, string]> = [
    ['A', 'length % 4 == 1'],
    ['AAAAA', 'length % 4 == 1'],
    ['Zg==', 'padding is not allowed'],
    ['Zg=', 'padding is not allowed'],
    ['Zm8=', 'padding is not allowed'],
    ['Zh', 'non-canonical: unused trailing bits are not zero'],
    ['Zm9', 'non-canonical: unused trailing bits are not zero'],
    ['+/8', 'standard alphabet characters + and / are not allowed'],
    ['Zm 9v', 'whitespace is not allowed'],
    ['Zm9v\n', 'whitespace is not allowed'],
    ['Zm9v.', 'character outside the alphabet'],
    ['Zm9vé', 'non-ASCII character'],
  ];
  for (const [input] of invalid) if (!decodeRejects(input)) throw new Error(`b64 accepted ${input}`);
  return {
    description:
      'base64url without padding (RFC 4648 §5). Decoders MUST reject padding, characters outside the base64url alphabet (including whitespace and +/), lengths with len % 4 == 1, and non-zero unused trailing bits.',
    encode: encode.map((b) => ({ bytes_hex: hex(b), b64: b64(b) })),
    decode_invalid: invalid.map(([input, reason]) => ({ input, reason })),
  };
}

export function encodingVectors(): VectorFile {
  const lpCases: Array<[string, Bytes]> = [
    ['empty', new Uint8Array(0)],
    ['ascii string "carom/v1/env"', utf8('carom/v1/env')],
    ['UTF-8 string "Michał 🎉"', utf8('Michał 🎉')],
    ['300 bytes of 0xab', new Uint8Array(300).fill(0xab)],
  ];
  const u64Cases: Array<number | bigint> = [0, 1, 255, 256, 60, 604_800, 1_791_648_000_000, Number.MAX_SAFE_INTEGER, 2n ** 64n - 1n];
  return {
    description: 'lp(x) = u32be(len(x)) || x with strings UTF-8 encoded first; u64(n) = 8-byte big-endian (SPEC §1).',
    lp: lpCases.map(([name, x]) => ({ name, input_hex: hex(x), lp_hex: hex(lp(x)) })),
    u64: u64Cases.map((n) => ({ n: n.toString(), u64_hex: hex(u64(n)) })),
  };
}

export function deviceIdVectors(): VectorFile {
  const ids = { phone: PHONE, mac: MAC, laptop: LAPTOP, tablet: TABLET, outsider: OUTSIDER };
  const invalid: Array<[string, string]> = [
    ['dev_', 'too short'],
    ['dev_AAAAAAAAAAAAAAAAAAAAA', '21 characters after dev_'],
    ['dev_AAAAAAAAAAAAAAAAAAAAAAA', '23 characters after dev_'],
    ['Dev_AAAAAAAAAAAAAAAAAAAAAA', 'prefix is case-sensitive'],
    ['dev_AAAAAAAAAAAAAAAAAAAAAB', 'non-canonical b64 (trailing bits)'],
    ['dev_AAAAAAAAAAAAAAAAAAAA+A', 'character outside base64url'],
    ['dev_AAAAAAAAAAAAAAAAAAAAA=', 'padding'],
  ];
  for (const [s] of invalid) if (isDeviceId(s)) throw new Error(`accepted ${s}`);
  return {
    description:
      'device_id = "dev_" || b64(SHA-256(sign_pub)[0..16]) (SPEC §2.2). Fixture identities used by every other vector file: sign_sk is the RFC 8032 Ed25519 32-byte seed, kx_sk the raw X25519 scalar (clamped at use per RFC 7748). Each secret is SHA-256("carom/v1/test-vectors/<name>.sign" or ".kx").',
    identities: Object.entries(ids).map(([name, id]) => ({
      name,
      ...identityJson(id),
      sha256_sign_pub_hex: hex(sha256(id.signPub)),
      expected_device_id: deviceIdFromSignPub(id.signPub),
    })),
    invalid_device_ids: invalid.map(([value, reason]) => ({ value, reason })),
  };
}

export function kdfVectors(): VectorFile {
  const salt = seed('kdf/salt');
  const pairSecret = seed('kdf/pair-secret');
  const setupToken = 'correct-horse-battery-staple-0123456789';
  const ephSk = seed('kdf/eph');
  const ephPub = x25519PublicKey(ephSk);
  const ss = x25519Shared(ephSk, MAC.kxPub)!;
  const psk = pairKey(pairSecret);
  return {
    description:
      'HKDF-SHA256 derivations (RFC 5869). An empty salt is the zero-length string, which HKDF treats as 32 zero bytes. info strings are ASCII. All outputs are 32 bytes.',
    msg_key: [K1, K2].map((k) => ({
      group_key_hex: hex(k),
      salt_hex: hex(salt),
      info: 'carom/v1/msg',
      msg_key_hex: hex(msgKey(k, salt)),
    })),
    collapse_key: [K1, K2].map((k) => ({
      group_key_hex: hex(k),
      salt_hex: '',
      info: 'carom/v1/collapse',
      collapse_key_hex: hex(collapseKey(k)),
    })),
    pair_key: [{ pair_secret_hex: hex(pairSecret), salt_hex: '', info: 'carom/v1/pair', k_pair_hex: hex(psk) }],
    token_key: [
      { setup_token: setupToken, ikm_hex: hex(utf8(setupToken)), salt_hex: '', info: 'carom/v1/token-key', token_key_hex: hex(tokenKey(setupToken)) },
    ],
    box_key: [new Uint8Array(0), psk].map((p) => ({
      eph_sk_hex: hex(ephSk),
      eph_pub_hex: hex(ephPub),
      rcpt_kx_pub_hex: hex(MAC.kxPub),
      rcpt_kx_sk_hex: hex(MAC.kxSk),
      shared_secret_hex: hex(ss),
      psk_hex: hex(p),
      ikm_hex: hex(concat(ss, p)),
      salt_hex: hex(concat(ephPub, MAC.kxPub)),
      info: 'carom/v1/box',
      box_key_hex: hex(boxKey(ss, p, ephPub, MAC.kxPub)),
    })),
  };
}

export function collapseVectors(): VectorFile {
  const cases: Array<[string, Bytes, string, string]> = [
    ['clipboard', K1, PHONE.deviceId, 'clip'],
    ['device status', K1, MAC.deviceId, 'status'],
    ['notification key (ascii)', K1, PHONE.deviceId, 'n:0|com.whatsapp|1|null|10123'],
    ['notification key (unicode)', K1, PHONE.deviceId, 'n:Zażółć gęślą jaźń 🎉'],
    ['empty real key', K1, PHONE.deviceId, ''],
    ['same key under kv 2 differs', K2, PHONE.deviceId, 'clip'],
  ];
  return {
    description:
      'collapse = b64(HMAC-SHA256(K_collapse(K), lp(from) || lp(real_key))[0..16]) with K_collapse(K) = HKDF(ikm=K, salt="", info="carom/v1/collapse", L=32) (SPEC §3.2).',
    cases: cases.map(([name, k, from, realKey]) => {
      const kc = collapseKey(k);
      const input = collapseInput(from, realKey);
      return {
        name,
        group_key_hex: hex(k),
        from,
        real_key: realKey,
        real_key_utf8_hex: hex(utf8(realKey)),
        collapse_key_hex: hex(kc),
        hmac_input_hex: hex(input),
        hmac_hex: hex(hmacSha256(kc, input)),
        collapse: collapseValue(k, from, realKey),
      };
    }),
  };
}

export function paddingVectors(): VectorFile {
  const lengths = [0, 1, 255, 256, 257, 512, 4095, 4096, 4097, 8192, 8193, 61_440, 61_441, 65_535, 65_536];
  return {
    description:
      'Payload padding (SPEC §3.1): append ASCII spaces (0x20) up to the next multiple of 256 while the length is <= 4096, and the next multiple of 4096 above that. A length that is already a multiple is unchanged (0 stays 0). Payloads over 65536 bytes are rejected before padding.',
    cases: lengths.map((n) => ({ input_length: n, padded_length: paddedLength(n) })),
    rejected: [{ input_length: 65_537, reason: 'exceeds the 65536-byte plaintext limit' }],
  };
}
