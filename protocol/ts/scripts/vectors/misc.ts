// Token, QR URI and UUIDv7 vectors (SPEC §6, §8, §3.1).

import { b64decode } from '../../src/b64.ts';
import { concat, fromHex, lp } from '../../src/bytes.ts';
import { tokenKey } from '../../src/kdf.ts';
import { hmacSha256 } from '../../src/primitives.ts';
import { buildPairUri, buildSetupUri, parseQrUri } from '../../src/qr.ts';
import { makeToken, parseToken, tokenHash, verifyToken } from '../../src/token.ts';
import { createUuidV7Generator, formatUuidV7, isUuidV7 } from '../../src/uuidv7.ts';
import { b64, hex, MAC, PHONE, seed, seededRng, T0, type VectorFile } from './fixtures.ts';

const SETUP_TOKEN = 'correct-horse-battery-staple-0123456789';

export function tokenVectors(): VectorFile {
  const key = tokenKey(SETUP_TOKEN);
  const make = [PHONE, MAC].map((d, i) => {
    const rand = seed(`token/rand-${i + 1}`);
    const input = concat(lp(d.deviceId), lp(rand));
    const token = makeToken(key, d.deviceId, { rand });
    return {
      device_id: d.deviceId,
      rand_hex: hex(rand),
      mac_input_hex: hex(input),
      hmac_hex: hex(hmacSha256(key, input)),
      mac16_hex: hex(hmacSha256(key, input).subarray(0, 16)),
      token,
      token_hash_hex: hex(tokenHash(token)),
    };
  });
  const good = make[0]!.token;
  const [prefix, id, rand, mac] = good.split('.') as [string, string, string, string];
  const flippedMac = b64(b64decode(mac).map((x, i) => (i === 0 ? x ^ 1 : x)));
  const verify = [
    { name: 'valid', setup_token: SETUP_TOKEN, token: good, expected_device_id: PHONE.deviceId },
    { name: 'valid (second device)', setup_token: SETUP_TOKEN, token: make[1]!.token, expected_device_id: MAC.deviceId },
    { name: 'different SETUP_TOKEN (after reset)', setup_token: SETUP_TOKEN + '!', token: good, expected_device_id: null },
    { name: 'mac first bit flipped', setup_token: SETUP_TOKEN, token: [prefix, id, rand, flippedMac].join('.'), expected_device_id: null },
    { name: 'device id swapped', setup_token: SETUP_TOKEN, token: [prefix, MAC.deviceId, rand, mac].join('.'), expected_device_id: null },
  ];
  const malformed: Array<[string, string]> = [
    ['', 'empty'],
    ['ct1', 'one part'],
    [[prefix, id, rand].join('.'), 'three parts'],
    [[prefix, id, rand, mac, mac].join('.'), 'five parts'],
    [['ct2', id, rand, mac].join('.'), 'wrong prefix'],
    [['CT1', id, rand, mac].join('.'), 'prefix is case-sensitive'],
    [[prefix, 'dev_short', rand, mac].join('.'), 'invalid device id'],
    [[prefix, id, rand + '=', mac].join('.'), 'padded rand'],
    [[prefix, id, b64(new Uint8Array(31)), mac].join('.'), 'rand 31 bytes'],
    [[prefix, id, rand, b64(new Uint8Array(15))].join('.'), 'mac 15 bytes'],
    [[prefix, id, rand, b64(new Uint8Array(17))].join('.'), 'mac 17 bytes'],
    [good + ' ', 'trailing space'],
  ];
  for (const v of verify) {
    if (verifyToken(v.token, tokenKey(v.setup_token)) !== v.expected_device_id) throw new Error(`token vector "${v.name}" mismatch`);
  }
  for (const [t] of malformed) if (parseToken(t) !== null) throw new Error(`token parsed: ${t}`);
  return {
    description:
      'Device tokens (SPEC §6): token = "ct1." || device_id || "." || b64(rand32) || "." || b64(mac16); token_key = HKDF(ikm = UTF8(SETUP_TOKEN), salt = "", info = "carom/v1/token-key", L = 32); mac16 = HMAC-SHA256(token_key, lp(device_id) || lp(rand32))[0..16]. MAC comparison MUST be constant-time. token_hash = SHA-256(UTF-8 bytes of the whole token string), as stored by the Hub.',
    setup_token: SETUP_TOKEN,
    token_key_hex: hex(key),
    make,
    verify,
    malformed: malformed.map(([token, reason]) => ({ token, reason })),
  };
}

export function qrVectors(): VectorFile {
  const hub = 'https://carom-hub.example.workers.dev';
  const secret = seed('qr/pair-secret');
  const s = b64(secret);
  const pid = 'AAECAwQFBgcICQoLDA0ODw';
  const setupToken = 'Sx9+/=&é token with spaces 0123456789';
  const build = [
    { kind: 'setup', hub, setup_token: 'abcdefghijklmnopqrstuvwxyz0123456789', uri: buildSetupUri(hub, 'abcdefghijklmnopqrstuvwxyz0123456789') },
    { kind: 'setup', hub: 'https://hub.example.com:8443/carom', setup_token: setupToken, uri: buildSetupUri('https://hub.example.com:8443/carom', setupToken) },
    { kind: 'pair', hub, pair_id: 'AAECAwQFBgcICQoLDA0ODw', pair_secret_hex: hex(secret), uri: buildPairUri(hub, 'AAECAwQFBgcICQoLDA0ODw', secret) },
    { kind: 'pair', hub: 'http://localhost:8787', pair_id: 'AAECAwQFBgcICQoLDA0ODx', pair_secret_hex: hex(secret), uri: buildPairUri('http://localhost:8787', 'AAECAwQFBgcICQoLDA0ODx', secret) },
  ];
  const enc = 'https%3A%2F%2Fcarom-hub.example.workers.dev';
  const valid = [
    ...build.map((b) => b.uri),
    `  carom://pair?v=1&h=${enc}&c=${pid}&s=${s}\n`,
    `carom://pair?s=${s}&c=${pid}&h=${enc}&v=1`,
    `carom://pair?v=1&h=https://carom-hub.example.workers.dev&c=${pid}&s=${s}`,
    `carom://setup?v=1&h=${enc.toLowerCase()}&t=%61bcdefghijklmnopqrstuvwxyz0123456789`,
    `carom://setup?v=1&h=${enc}&t=abcdefghijklmnopqrstuvwxyz01234=6789`,
  ];
  const invalid: Array<[string, string]> = [
    [`carom://pair?v=2&h=${enc}&c=${pid}&s=${s}`, 'unsupported version'],
    [`carom://pair?h=${enc}&c=${pid}&s=${s}`, 'missing v'],
    [`carom://pair?v=1&h=${enc}&c=${pid}`, 'missing s'],
    [`carom://pair?v=1&v=1&h=${enc}&c=${pid}&s=${s}`, 'duplicate parameter'],
    [`carom://pair?v=1&h=${enc}&c=${pid}&s=${s}&x=1`, 'unknown parameter'],
    [`carom://pair?v=1&h=${enc}&c=${pid}&s=${s}&`, 'empty parameter'],
    [`CAROM://pair?v=1&h=${enc}&c=${pid}&s=${s}`, 'scheme is case-sensitive'],
    [`https://pair?v=1&h=${enc}&c=${pid}&s=${s}`, 'wrong scheme'],
    [`carom://join?v=1&h=${enc}&c=${pid}&s=${s}`, 'unknown kind'],
    [`carom://pair?v=1&h=${enc}&c=${pid}&s=${s}#frag`, 'fragment (becomes part of s)'],
    [`carom://pair?v=1&h=http%3A%2F%2Fcarom-hub.example.workers.dev&c=${pid}&s=${s}`, 'http hub on a non-loopback host'],
    [`carom://pair?v=1&h=https%3A%2F%2Fuser%3Apw%40hub.example.com&c=${pid}&s=${s}`, 'credentials in the hub url'],
    [`carom://pair?v=1&h=https%3A%2F%2Fhub.example.com%2F%3Fq%3D1&c=${pid}&s=${s}`, 'query in the hub url'],
    [`carom://pair?v=1&h=not-a-url&c=${pid}&s=${s}`, 'hub is not a url'],
    [`carom://pair?v=1&h=${enc}&c=${pid.slice(0, 20)}%2Fb&s=${s}`, 'pair_id with a character outside [A-Za-z0-9_-]'],
    [`carom://pair?v=1&h=${enc}&c=&s=${s}`, 'empty pair_id'],
    [`carom://pair?v=1&h=${enc}&c=${pid.slice(0, 21)}&s=${s}`, 'pair_id 21 characters'],
    [`carom://pair?v=1&h=${enc}&c=${pid}A&s=${s}`, 'pair_id 23 characters'],
    [`carom://pair?v=1&h=${enc}&c=abc&s=${s}`, 'pair_id is not 22 characters'],
    [`carom://pair?v=1&h=${enc}&c=${pid}&s=${b64(new Uint8Array(31))}`, 'pair secret 31 bytes'],
    [`carom://pair?v=1&h=${enc}&c=${pid}&s=${s}%3D`, 'padded pair secret'],
    [`carom://pair?v=1&h=${enc}&c=${pid}&s=${s.slice(0, -1)}+`, 'raw + in a value'],
    [`carom://pair?v=1&h=${enc}&c=${pid}&s=${s}%2`, 'truncated percent escape'],
    [`carom://pair?v=1&h=${enc}&c=${pid}&s=${s}%ZZ`, 'invalid percent escape'],
    [`carom://setup?v=1&h=${enc}&t=short-token`, 'setup token shorter than 32 characters'],
    [`carom://setup?v=1&h=${enc}&t=%FF${'a'.repeat(32)}`, 'setup token is not valid UTF-8'],
    [`carom://setup?v=1&h=${enc}&t=${'a'.repeat(32)}&c=${pid}`, 'pair parameter in a setup uri'],
  ];
  for (const uri of valid) if (parseQrUri(uri) === null) throw new Error(`qr rejected: ${uri}`);
  for (const [uri] of invalid) if (parseQrUri(uri) !== null) throw new Error(`qr accepted: ${uri}`);
  return {
    description:
      'QR / pasted URIs (SPEC §8). Builders percent-encode every UTF-8 byte outside A-Z a-z 0-9 - . _ ~ with uppercase hex, in the parameter order v, h, t (setup) or v, h, c, s (pair). Parsers trim surrounding whitespace and are strict: scheme and kind are case-sensitive, the parameter set must be exact (any order, no duplicates, no unknowns), v must be "1"; values may contain percent-escapes (either hex case) and the raw characters A-Za-z0-9-._~!$\'()*,;:@/?= only, so a raw "+" (or any other character outside that set, e.g. "#", space, non-ASCII) is invalid, a raw "=" inside a value is allowed, and "+" never means space; decoded values must be valid UTF-8. h must be an https URL (http only for localhost, 127.0.0.1 and [::1]) without credentials, query or fragment. t must be at least 32 characters without control characters. c matches [A-Za-z0-9_-]{22} (b64 of 16 bytes). s is the canonical b64 of exactly 32 bytes.',
    build,
    parse_valid: valid.map((uri) => {
      const p = parseQrUri(uri)!;
      return p.kind === 'setup'
        ? { uri, expected: { kind: p.kind, hub: p.hub, setup_token: p.setupToken } }
        : { uri, expected: { kind: p.kind, hub: p.hub, pair_id: p.pairId, pair_secret_hex: hex(p.pairSecret) } };
    }),
    parse_invalid: invalid.map(([uri, reason]) => ({ uri, reason })),
  };
}

export function uuidVectors(): VectorFile {
  const format = [
    { ms: 0, counter: 0, rand_b_hex: '0000000000000000' },
    { ms: T0, counter: 0x123, rand_b_hex: 'ffffffffffffffff' },
    { ms: 2 ** 48 - 1, counter: 0xfff, rand_b_hex: hex(seed('uuid/rand').subarray(0, 8)) },
  ].map((c) => ({ ...c, uuid: formatUuidV7(c.ms, c.counter, fromHex(c.rand_b_hex)) }));

  const clock = [T0, T0, T0, T0 + 1, T0 - 5, T0 - 5, T0 + 2];
  let tick = 0;
  const gen = createUuidV7Generator({ now: () => clock[tick++]!, rng: seededRng('uuid/generator') });
  const sequence = clock.map((now) => ({ clock_ms: now, uuid: gen() }));
  for (let i = 1; i < sequence.length; i++) {
    if (!(sequence[i - 1]!.uuid < sequence[i]!.uuid)) throw new Error('uuid sequence not monotonic');
  }
  const invalid: Array<[string, string]> = [
    ['0192A4B1-7C3D-7E5F-8A9B-0C1D2E3F4A5B', 'uppercase'],
    ['0192a4b1-7c3d-4e5f-8a9b-0c1d2e3f4a5b', 'version 4'],
    ['0192a4b1-7c3d-7e5f-ca9b-0c1d2e3f4a5b', 'variant is not 10xx'],
    ['0192a4b17c3d7e5f8a9b0c1d2e3f4a5b', 'no hyphens'],
    ['{0192a4b1-7c3d-7e5f-8a9b-0c1d2e3f4a5b}', 'braces'],
    ['0192a4b1-7c3d-7e5f-8a9b-0c1d2e3f4a5', 'too short'],
  ];
  for (const [s] of invalid) if (isUuidV7(s)) throw new Error(`uuid accepted ${s}`);
  return {
    description:
      'msg_id is a lowercase hyphenated UUIDv7 (RFC 9562), monotonic per sender. Receivers and the Hub order msg_ids by plain string comparison, which equals byte order for valid ids. Reference layout (RFC 9562 §6.2 method 1): unix_ts_ms (48 bits) | ver 0111 | 12-bit counter in rand_a | var 10 | 62 random bits (the low 62 bits of rand_b_hex). The counter is reseeded with a random 11-bit value (top bit clear) on each new millisecond and incremented otherwise; if the clock goes backwards the last timestamp is reused; on counter overflow the timestamp is advanced by 1 ms. Other implementations may use any RFC 9562 method 1 or 3 generator; only `format`, `valid`/`invalid` and the monotonic property are normative. `generator_sequence` documents this generator with rng = the test-vector seeded stream "uuid/generator" (10 bytes per id: 2 counter-seed bytes, 8 rand_b bytes; stream block i = SHA-256("carom/v1/test-vectors/uuid/generator/" || u32be(i))).',
    format,
    generator_sequence: sequence,
    invalid: invalid.map(([value, reason]) => ({ value, reason })),
  };
}
