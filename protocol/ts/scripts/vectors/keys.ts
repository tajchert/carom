// Box, pairing and rekey vectors (SPEC §2.4, §8, §5.5).

import { b64decode } from '../../src/b64.ts';
import { openBox, sealBox, type Box } from '../../src/box.ts';
import { concat, fromHex, utf8, type Bytes } from '../../src/bytes.ts';
import type { Identity } from '../../src/device.ts';
import { boxKey, pairKey } from '../../src/kdf.ts';
import {
  buildPairAccept,
  encodeWelcome,
  openWelcome,
  pairRequestInput,
  sealWelcome,
  verifyPairCandidate,
  welcomeAad,
  type Welcome,
} from '../../src/pairing.ts';
import { ed25519Sign, x25519PublicKey, x25519Shared } from '../../src/primitives.ts';
import { buildRekey, openRekey, rekeyBoxAad, rekeySigInput, type KeyringVersion } from '../../src/rekey.ts';
import { b64, hex, identityJson, K1, K2, LAPTOP, MAC, OUTSIDER, PHONE, seed, SIG_NOTE, TABLET, type VectorFile } from './fixtures.ts';

function boxIntermediates(ephSk: Bytes, rcptKxPub: Bytes, psk: Bytes) {
  const ephPub = x25519PublicKey(ephSk);
  const ss = x25519Shared(ephSk, rcptKxPub)!;
  return {
    eph_sk_hex: hex(ephSk),
    eph_pub_hex: hex(ephPub),
    shared_secret_hex: hex(ss),
    psk_hex: hex(psk),
    hkdf_ikm_hex: hex(concat(ss, psk)),
    hkdf_salt_hex: hex(concat(ephPub, rcptKxPub)),
    box_key_hex: hex(boxKey(ss, psk, ephPub, rcptKxPub)),
  };
}

function flipCt(box: Box): Box {
  const ct = b64decode(box.ct);
  ct[0]! ^= 1;
  return { epk: box.epk, ct: b64(ct) };
}

export function boxVectors(): VectorFile {
  const specs: Array<{ name: string; label: string; aad: Bytes; plaintext: Bytes; psk: Bytes }> = [
    { name: 'no psk', label: 'box-1', aad: utf8('carom test aad'), plaintext: utf8('hello box'), psk: new Uint8Array(0) },
    { name: 'with psk', label: 'box-2', aad: utf8('carom test aad'), plaintext: utf8('hello box'), psk: seed('box/psk') },
    { name: 'empty aad, 32-byte plaintext', label: 'box-3', aad: new Uint8Array(0), plaintext: seed('box/pt'), psk: new Uint8Array(0) },
  ];
  const seals = specs.map((s) => {
    const ephSk = seed(`eph/${s.label}`);
    const box = sealBox({ rcptKxPub: MAC.kxPub, aad: s.aad, plaintext: s.plaintext, psk: s.psk, ephSk });
    return {
      name: s.name,
      rcpt_kx_sk_hex: hex(MAC.kxSk),
      rcpt_kx_pub_hex: hex(MAC.kxPub),
      ...boxIntermediates(ephSk, MAC.kxPub, s.psk),
      aad_hex: hex(s.aad),
      plaintext_hex: hex(s.plaintext),
      ct_hex: hex(b64decode(box.ct)),
      box,
    };
  });
  const [noPsk, withPsk] = seals as [(typeof seals)[number], (typeof seals)[number]];
  const lowOrder = (bytes: Bytes): Box => ({ epk: b64(bytes), ct: noPsk.box.ct });
  const one = new Uint8Array(32);
  one[0] = 1;
  const open = (name: string, box: unknown, kxSk: Bytes, aad: string, psk: string, expected: string | null) => ({
    name,
    box,
    rcpt_kx_sk_hex: hex(kxSk),
    aad_hex: aad,
    psk_hex: psk,
    expected_plaintext_hex: expected,
  });
  const cases = [
    open('valid, no psk', noPsk.box, MAC.kxSk, noPsk.aad_hex, '', noPsk.plaintext_hex),
    open('valid, with psk', withPsk.box, MAC.kxSk, withPsk.aad_hex, withPsk.psk_hex, withPsk.plaintext_hex),
    open('psk missing', withPsk.box, MAC.kxSk, withPsk.aad_hex, '', null),
    open('wrong psk', withPsk.box, MAC.kxSk, withPsk.aad_hex, hex(seed('box/other-psk')), null),
    open('wrong aad', noPsk.box, MAC.kxSk, hex(utf8('carom test aaD')), '', null),
    open('ct first bit flipped', flipCt(noPsk.box), MAC.kxSk, noPsk.aad_hex, '', null),
    open('wrong recipient key', noPsk.box, LAPTOP.kxSk, noPsk.aad_hex, '', null),
    open('epk is the zero point (all-zero shared secret)', lowOrder(new Uint8Array(32)), MAC.kxSk, noPsk.aad_hex, '', null),
    open('epk is the low-order point 1 (all-zero shared secret)', lowOrder(one), MAC.kxSk, noPsk.aad_hex, '', null),
    open('epk 31 bytes', { epk: b64(new Uint8Array(31)), ct: noPsk.box.ct }, MAC.kxSk, noPsk.aad_hex, '', null),
  ];
  for (const c of cases) {
    const pt = openBox(c.box, {
      kxSk: fromHex(c.rcpt_kx_sk_hex),
      aad: fromHex(c.aad_hex),
      psk: fromHex(c.psk_hex),
    });
    const got = pt === null ? null : hex(pt);
    if (got !== c.expected_plaintext_hex) throw new Error(`box vector "${c.name}" mismatch`);
  }
  return {
    description:
      'Box (SPEC §2.4): ss = X25519(eph_sk, rcpt_kx_pub), rejected if all-zero; key = HKDF(ikm = ss || psk, salt = eph_pub || rcpt_kx_pub, info = "carom/v1/box", L = 32); ct = ChaCha20-Poly1305(key, 12 zero bytes, aad, plaintext); box = {epk: b64(eph_pub), ct: b64(ct)}. The opener uses ss = X25519(rcpt_kx_sk, eph_pub) and its own kx_pub in the salt. An empty psk_hex means no psk. expected_plaintext_hex null means opening MUST fail.',
    seal_cases: seals,
    open_cases: cases,
  };
}

const PAIR_ID = 'p_4f8e2c1a9b7d';

export function pairingVectors(): VectorFile {
  const pairSecret = seed('pair/secret');
  const kPair = pairKey(pairSecret);
  const joiner = TABLET;
  const inviter = MAC;
  const accept = buildPairAccept(PAIR_ID, pairSecret, joiner);
  const candidate = { device_id: joiner.deviceId, sign_pub: accept.sign_pub, kx_pub: accept.kx_pub, mac: accept.mac, pop: accept.pop };

  const otherAccept = buildPairAccept(PAIR_ID, pairSecret, OUTSIDER);
  const candidateCases: Array<{ name: string; pair_secret_hex: string; candidate: Record<string, string>; expected: string }> = [
    { name: 'valid', pair_secret_hex: hex(pairSecret), candidate, expected: 'ok' },
    {
      name: 'valid without pop (the Hub does not relay it)',
      pair_secret_hex: hex(pairSecret),
      candidate: { device_id: candidate.device_id, sign_pub: candidate.sign_pub, kx_pub: candidate.kx_pub, mac: candidate.mac },
      expected: 'ok',
    },
    { name: 'wrong pair secret', pair_secret_hex: hex(seed('pair/other-secret')), candidate, expected: 'bad_mac' },
    { name: 'kx_pub substituted by the Hub', pair_secret_hex: hex(pairSecret), candidate: { ...candidate, kx_pub: b64(OUTSIDER.kxPub) }, expected: 'bad_mac' },
    {
      name: 'sign_pub substituted by the Hub (device_id updated to match)',
      pair_secret_hex: hex(pairSecret),
      candidate: { ...candidate, sign_pub: b64(OUTSIDER.signPub), device_id: OUTSIDER.deviceId },
      expected: 'bad_mac',
    },
    { name: 'pop from another key', pair_secret_hex: hex(pairSecret), candidate: { ...candidate, pop: otherAccept.pop }, expected: 'bad_pop' },
    { name: 'device_id does not derive from sign_pub', pair_secret_hex: hex(pairSecret), candidate: { ...candidate, device_id: LAPTOP.deviceId }, expected: 'device_id_mismatch' },
    { name: 'mac 31 bytes', pair_secret_hex: hex(pairSecret), candidate: { ...candidate, mac: b64(new Uint8Array(31)) }, expected: 'bad_shape' },
  ];
  for (const c of candidateCases) {
    const r = verifyPairCandidate(PAIR_ID, fromHex(c.pair_secret_hex), c.candidate);
    if ((r.ok ? 'ok' : r.error) !== c.expected) throw new Error(`pairing vector "${c.name}" mismatch`);
  }

  const welcome: Welcome = { keys: [{ kv: 1, k: b64(K1) }, { kv: 2, k: b64(K2) }], inviter: inviter.deviceId };
  const ephSk = seed('eph/welcome');
  const box = sealWelcome({ pairId: PAIR_ID, candidateId: joiner.deviceId, candidateKxPub: joiner.kxPub, pairSecret, welcome, ephSk });
  const welcomeOpenCases = [
    { name: 'valid', pair_id: PAIR_ID, self_id: joiner.deviceId, pair_secret_hex: hex(pairSecret), box, expected: 'ok' },
    { name: 'wrong pair secret', pair_id: PAIR_ID, self_id: joiner.deviceId, pair_secret_hex: hex(seed('pair/other-secret')), box, expected: 'decrypt_failed' },
    { name: 'wrong pair_id', pair_id: 'p_other', self_id: joiner.deviceId, pair_secret_hex: hex(pairSecret), box, expected: 'decrypt_failed' },
    { name: 'wrong candidate id in aad', pair_id: PAIR_ID, self_id: LAPTOP.deviceId, pair_secret_hex: hex(pairSecret), box, expected: 'decrypt_failed' },
  ];
  for (const c of welcomeOpenCases) {
    const r = openWelcome(c.box, c.pair_id, c.self_id, joiner.kxSk, fromHex(c.pair_secret_hex));
    if ((r.ok ? 'ok' : r.error) !== c.expected) throw new Error(`welcome vector "${c.name}" mismatch`);
  }

  return {
    description:
      'Pairing (SPEC §8). k_pair = HKDF(ikm = pair_secret, salt = "", info = "carom/v1/pair", L = 32). request_input = lp("carom/v1/pair-req") || lp(pair_id) || lp(sign_pub) || lp(kx_pub) with raw 32-byte keys; mac = HMAC-SHA256(k_pair, request_input) (all 32 bytes); pop = Ed25519(sign_sk, request_input). Candidate check order: shape (bad_shape), mac (bad_mac), pop when present (bad_pop), device_id(sign_pub) == device_id (device_id_mismatch). Welcome: Box with psk = k_pair, aad = lp("carom/v1/welcome") || lp(pair_id) || lp(B.id), plaintext = welcome JSON; opening errors are decrypt_failed or bad_welcome. ' +
      SIG_NOTE,
    pair_id: PAIR_ID,
    pair_secret_hex: hex(pairSecret),
    k_pair_hex: hex(kPair),
    joiner: identityJson(joiner),
    inviter: identityJson(inviter),
    request: {
      request_input_hex: hex(pairRequestInput(PAIR_ID, joiner.signPub, joiner.kxPub)),
      mac_hex: hex(b64decode(accept.mac)),
      pop_hex: hex(b64decode(accept.pop)),
      pop_kind: 'deterministic_rfc8032',
      accept_body: accept,
    },
    candidate_cases: candidateCases,
    welcome: {
      welcome,
      plaintext_hex: hex(encodeWelcome(welcome)),
      aad_hex: hex(welcomeAad(PAIR_ID, joiner.deviceId)),
      rcpt_kx_pub_hex: hex(joiner.kxPub),
      ...boxIntermediates(ephSk, joiner.kxPub, kPair),
      ct_hex: hex(b64decode(box.ct)),
      box,
    },
    welcome_open_cases: welcomeOpenCases,
  };
}

export function rekeyVectors(): VectorFile {
  const by = MAC;
  const kv = 2;
  const members: Identity[] = [PHONE, MAC, LAPTOP];
  const ephSks = new Map([...members, OUTSIDER].map((m) => [m.deviceId, seed(`eph/rekey/${m.deviceId}`)]));
  const version = buildRekey({ by, kv, groupKey: K2, members: members.map((m) => ({ id: m.deviceId, kxPub: m.kxPub })), ephSks });

  const directory = Object.fromEntries([PHONE, MAC, LAPTOP].map((d) => [d.deviceId, b64(d.signPub)]));
  const ids = Object.keys(version.boxes);
  const [firstId, secondId] = [...ids].sort() as [string, string];
  const swapped: KeyringVersion = {
    ...version,
    boxes: { ...version.boxes, [firstId]: version.boxes[secondId]!, [secondId]: version.boxes[firstId]! },
  };
  const tamperedBox: KeyringVersion = { ...version, boxes: { ...version.boxes, [PHONE.deviceId]: flipCt(version.boxes[PHONE.deviceId]!) } };
  const forged = buildRekey({
    by: { ...OUTSIDER, deviceId: by.deviceId },
    kv,
    groupKey: K2,
    members: members.map((m) => ({ id: m.deviceId, kxPub: m.kxPub })),
    ephSks,
  });
  const byOutsider = buildRekey({
    by: OUTSIDER,
    kv,
    groupKey: K2,
    members: [...members, OUTSIDER].map((m) => ({ id: m.deviceId, kxPub: m.kxPub })),
    ephSks,
  });

  const misdirectedBoxes = {
    ...version.boxes,
    [PHONE.deviceId]: sealBox({
      rcptKxPub: LAPTOP.kxPub,
      aad: rekeyBoxAad(by.deviceId, PHONE.deviceId, kv),
      plaintext: K2,
      ephSk: seed('eph/rekey/misdirected'),
    }),
  };
  const misdirected: KeyringVersion = {
    kv,
    by: by.deviceId,
    boxes: misdirectedBoxes,
    sig: b64(ed25519Sign(by.signSk, rekeySigInput(by.deviceId, kv, misdirectedBoxes))),
  };

  type Case = { name: string; self: Identity; version: unknown; directory: Record<string, string>; expected: string };
  const cases: Case[] = [
    ...members.map((m) => ({ name: `valid, opened by ${m === PHONE ? 'phone' : m === MAC ? 'mac' : 'laptop'}`, self: m, version, directory, expected: 'ok' })),
    { name: 'signer not a member under the previous key', self: PHONE, version: byOutsider, directory, expected: 'untrusted_signer' },
    { name: 'signed with a key other than by\'s sign_pub', self: PHONE, version: forged, directory, expected: 'bad_signature' },
    { name: 'kv changed', self: PHONE, version: { ...version, kv: 3 }, directory, expected: 'bad_signature' },
    { name: 'one box ct tampered', self: LAPTOP, version: tamperedBox, directory, expected: 'bad_signature' },
    { name: 'two boxes swapped between ids', self: PHONE, version: swapped, directory, expected: 'bad_signature' },
    { name: 'sig first bit flipped', self: PHONE, version: { ...version, sig: b64(b64decode(version.sig).map((x, i) => (i === 0 ? x ^ 1 : x))) }, directory, expected: 'bad_signature' },
    { name: 'validly signed box that does not open for this device', self: PHONE, version: misdirected, directory, expected: 'decrypt_failed' },
    { name: 'no box for this device', self: TABLET, version, directory, expected: 'no_box' },
    { name: 'malformed: empty boxes', self: PHONE, version: { ...version, boxes: {} }, directory, expected: 'bad_shape' },
  ];
  const out = cases.map((c) => {
    const r = openRekey(c.version, { selfId: c.self.deviceId, kxSk: c.self.kxSk, directory: new Map(Object.entries(c.directory).map(([id, p]) => [id, b64decode(p)])) });
    const got = r.ok ? 'ok' : r.error;
    if (got !== c.expected) throw new Error(`rekey vector "${c.name}": expected ${c.expected}, got ${got}`);
    return {
      name: c.name,
      self_id: c.self.deviceId,
      self_kx_sk_hex: hex(c.self.kxSk),
      directory: c.directory,
      version: c.version,
      expected: c.expected,
      ...(r.ok ? { expected_group_key_hex: hex(r.groupKey), expected_kv: r.kv } : {}),
    };
  });

  return {
    description:
      'Rekey (SPEC §5.5). For each member D (including the rekeying device R): box_D = Box(rcpt = D.kx_pub, no psk, aad = lp("carom/v1/rekey") || lp(R.id) || lp(D.id) || lp(u64(kv\')), plaintext = K\' (32 bytes)). sig = Ed25519(R.sign_sk, lp("carom/v1/rekey") || lp(R.id) || u64(kv\') || lp(D1.id) || lp(epk_D1) || lp(ct_D1) || ...) with ids sorted ascending by byte value, epk and ct as RAW decoded bytes, and u64(kv\') NOT length-prefixed. Open order: shape (bad_shape); by is a member under the previous key with device_id(sign_pub) == by (untrusted_signer); signature (bad_signature); own box present (no_box); box opens (decrypt_failed); 32-byte key (bad_key). ' +
      SIG_NOTE,
    by: identityJson(by),
    kv,
    new_group_key_hex: hex(K2),
    previous_group_key_hex: hex(K1),
    members: members.map((m) => ({ device_id: m.deviceId, kx_pub_hex: hex(m.kxPub), kx_sk_hex: hex(m.kxSk) })),
    boxes: [...ids].sort().map((id) => {
      const m = members.find((x) => x.deviceId === id)!;
      return {
        device_id: id,
        aad_hex: hex(rekeyBoxAad(by.deviceId, id, kv)),
        ...boxIntermediates(ephSks.get(id)!, m.kxPub, new Uint8Array(0)),
        ct_hex: hex(b64decode(version.boxes[id]!.ct)),
        box: version.boxes[id],
      };
    }),
    sig_input_hex: hex(rekeySigInput(version.by, version.kv, version.boxes)),
    sig_hex: hex(b64decode(version.sig)),
    sig_kind: 'deterministic_rfc8032',
    keyring_put_body: version,
    open_cases: out,
  };
}
