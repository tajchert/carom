// Independent cross-check of the committed vectors with node:crypto.
// This file MUST NOT import anything from src/ or scripts/: every construction is rebuilt
// here from SPEC.md so a bug shared by the generator and src/ cannot hide.

import {
  createCipheriv,
  createDecipheriv,
  createHash,
  createHmac,
  createPrivateKey,
  createPublicKey,
  diffieHellman,
  hkdfSync,
  sign as nodeSign,
  verify as nodeVerify,
  type KeyObject,
} from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const DIR = join(import.meta.dirname, '..', '..', 'vectors');
type Json = any;
const load = (name: string): Json => JSON.parse(readFileSync(join(DIR, name), 'utf8'));

// ---- encodings (SPEC §1) ----
const hx = (s: string): Buffer => Buffer.from(s, 'hex');
const ub = (s: string): Buffer => Buffer.from(s, 'base64url');
const b64 = (b: Uint8Array): string => Buffer.from(b).toString('base64url');
const utf = (s: string): Buffer => Buffer.from(s, 'utf8');
const cat = (...p: Uint8Array[]): Buffer => Buffer.concat(p);
function lp(x: Uint8Array | string): Buffer {
  const b = typeof x === 'string' ? utf(x) : Buffer.from(x);
  const len = Buffer.alloc(4);
  len.writeUInt32BE(b.length);
  return cat(len, b);
}
function u64(n: number | bigint): Buffer {
  const b = Buffer.alloc(8);
  b.writeBigUInt64BE(BigInt(n));
  return b;
}
function strictB64(s: string): Buffer | null {
  if (!/^[A-Za-z0-9_-]*$/.test(s) || s.length % 4 === 1) return null;
  const b = ub(s);
  return b64(b) === s ? b : null;
}

// ---- primitives via node:crypto ----
const ED_PKCS8 = hx('302e020100300506032b657004220420');
const X_PKCS8 = hx('302e020100300506032b656e04220420');
const ED_SPKI = hx('302a300506032b6570032100');
const X_SPKI = hx('302a300506032b656e032100');

const edPriv = (seed: Buffer): KeyObject => createPrivateKey({ key: cat(ED_PKCS8, seed), format: 'der', type: 'pkcs8' });
const edPub = (pub: Uint8Array): KeyObject => createPublicKey({ key: cat(ED_SPKI, pub), format: 'der', type: 'spki' });
const xPriv = (sk: Buffer): KeyObject => createPrivateKey({ key: cat(X_PKCS8, sk), format: 'der', type: 'pkcs8' });
const xPub = (pub: Uint8Array): KeyObject => createPublicKey({ key: cat(X_SPKI, pub), format: 'der', type: 'spki' });
const rawPub = (k: KeyObject): Buffer => (k.export({ format: 'der', type: 'spki' }) as Buffer).subarray(12);

const sha256 = (d: Uint8Array): Buffer => createHash('sha256').update(d).digest();
const hmac = (k: Uint8Array, d: Uint8Array): Buffer => createHmac('sha256', k).update(d).digest();
const hkdf = (ikm: Uint8Array, salt: Uint8Array, info: string): Buffer =>
  Buffer.from(hkdfSync('sha256', ikm, salt, utf(info), 32));
const edVerify = (pub: Uint8Array, msg: Uint8Array, sig: Uint8Array): boolean => {
  try {
    return sig.length === 64 && nodeVerify(null, msg, edPub(pub), sig);
  } catch {
    return false;
  }
};
const NONCE = Buffer.alloc(12);
function aeadSeal(key: Uint8Array, aad: Uint8Array, pt: Uint8Array): Buffer {
  const c = createCipheriv('chacha20-poly1305', key, NONCE, { authTagLength: 16 });
  c.setAAD(aad, { plaintextLength: pt.length });
  return cat(c.update(pt), c.final(), c.getAuthTag());
}
function aeadOpen(key: Uint8Array, aad: Uint8Array, ct: Uint8Array): Buffer | null {
  if (ct.length < 16) return null;
  try {
    const d = createDecipheriv('chacha20-poly1305', key, NONCE, { authTagLength: 16 });
    d.setAAD(aad, { plaintextLength: ct.length - 16 });
    d.setAuthTag(ct.subarray(ct.length - 16));
    return cat(d.update(ct.subarray(0, ct.length - 16)), d.final());
  } catch {
    return null;
  }
}
function x25519(sk: Buffer, pub: Uint8Array): Buffer | null {
  try {
    const ss = diffieHellman({ privateKey: xPriv(sk), publicKey: xPub(pub) });
    return ss.every((x) => x === 0) ? null : ss;
  } catch {
    return null;
  }
}
const deviceId = (signPub: Uint8Array): string => 'dev_' + b64(sha256(signPub).subarray(0, 16));

// ---- SPEC constructions ----
function sealAad(tag: string, header: Uint8Array[], kv: number, salt: Uint8Array): Buffer {
  return cat(lp(tag), ...header.map((h) => lp(h)), lp(u64(kv)), lp(salt));
}
function envHeader(e: Json): Buffer[] {
  return [u64(e.v), utf(e.msg_id), utf(e.from), cat(...e.to.map((t: string) => lp(t))), utf(e.collapse ?? ''), u64(e.ttl_s)];
}
const envAad = (e: Json): Buffer => sealAad('carom/v1/env', envHeader(e), e.kv, ub(e.salt));
const msgKey = (k: Uint8Array, salt: Uint8Array): Buffer => hkdf(k, salt, 'carom/v1/msg');
function boxOpen(box: Json, kxSk: Buffer, aad: Uint8Array, psk: Uint8Array): Buffer | null {
  if (typeof box?.epk !== 'string' || typeof box?.ct !== 'string') return null;
  const epk = strictB64(box.epk);
  const ct = strictB64(box.ct);
  if (!epk || epk.length !== 32 || !ct) return null;
  const ss = x25519(kxSk, epk);
  if (!ss) return null;
  const myPub = rawPub(createPublicKey(xPriv(kxSk)));
  return aeadOpen(hkdf(cat(ss, psk), cat(epk, myPub), 'carom/v1/box'), aad, ct);
}
function paddedLength(n: number): number {
  return n <= 4096 ? Math.ceil(n / 256) * 256 : Math.ceil(n / 4096) * 4096;
}
const UUID7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('node:crypto cross-check of vectors', () => {
  it('b64', () => {
    const v = load('b64.json');
    for (const c of v.encode) {
      expect(b64(hx(c.bytes_hex))).toBe(c.b64);
      expect(strictB64(c.b64)?.toString('hex')).toBe(c.bytes_hex);
    }
    for (const c of v.decode_invalid) expect(strictB64(c.input), c.input).toBeNull();
  });

  it('lp and u64', () => {
    const v = load('encoding.json');
    for (const c of v.lp) expect(lp(hx(c.input_hex)).toString('hex')).toBe(c.lp_hex);
    for (const c of v.u64) expect(u64(BigInt(c.n)).toString('hex')).toBe(c.u64_hex);
  });

  it('identities and device ids', () => {
    for (const c of load('device_id.json').identities) {
      expect(rawPub(createPublicKey(edPriv(hx(c.sign_sk_hex)))).toString('hex')).toBe(c.sign_pub_hex);
      expect(rawPub(createPublicKey(xPriv(hx(c.kx_sk_hex)))).toString('hex')).toBe(c.kx_pub_hex);
      expect(sha256(hx(c.sign_pub_hex)).toString('hex')).toBe(c.sha256_sign_pub_hex);
      expect(deviceId(hx(c.sign_pub_hex))).toBe(c.expected_device_id);
      expect(c.device_id).toBe(c.expected_device_id);
    }
  });

  it('HKDF derivations', () => {
    const v = load('kdf.json');
    for (const c of v.msg_key) expect(msgKey(hx(c.group_key_hex), hx(c.salt_hex)).toString('hex')).toBe(c.msg_key_hex);
    for (const c of v.collapse_key) expect(hkdf(hx(c.group_key_hex), Buffer.alloc(0), c.info).toString('hex')).toBe(c.collapse_key_hex);
    for (const c of v.pair_key) expect(hkdf(hx(c.pair_secret_hex), Buffer.alloc(0), c.info).toString('hex')).toBe(c.k_pair_hex);
    for (const c of v.token_key) expect(hkdf(utf(c.setup_token), Buffer.alloc(0), c.info).toString('hex')).toBe(c.token_key_hex);
    for (const c of v.box_key) {
      const ss = x25519(hx(c.rcpt_kx_sk_hex), hx(c.eph_pub_hex))!;
      expect(ss.toString('hex')).toBe(c.shared_secret_hex);
      expect(x25519(hx(c.eph_sk_hex), hx(c.rcpt_kx_pub_hex))!.toString('hex')).toBe(c.shared_secret_hex);
      const ikm = cat(ss, hx(c.psk_hex));
      const salt = cat(hx(c.eph_pub_hex), hx(c.rcpt_kx_pub_hex));
      expect(ikm.toString('hex')).toBe(c.ikm_hex);
      expect(salt.toString('hex')).toBe(c.salt_hex);
      expect(hkdf(ikm, salt, 'carom/v1/box').toString('hex')).toBe(c.box_key_hex);
    }
  });

  it('collapse values', () => {
    for (const c of load('collapse.json').cases) {
      const kc = hkdf(hx(c.group_key_hex), Buffer.alloc(0), 'carom/v1/collapse');
      const input = cat(lp(c.from), lp(c.real_key));
      expect(kc.toString('hex')).toBe(c.collapse_key_hex);
      expect(input.toString('hex')).toBe(c.hmac_input_hex);
      expect(hmac(kc, input).toString('hex')).toBe(c.hmac_hex);
      expect(b64(hmac(kc, input).subarray(0, 16))).toBe(c.collapse);
    }
  });

  it('padding lengths', () => {
    for (const c of load('padding.json').cases) expect(paddedLength(c.input_length)).toBe(c.padded_length);
  });

  it('envelope seal: aad, ct, sig and payload', () => {
    for (const c of load('envelope_seal.json').cases) {
      const e = c.envelope;
      const aad = envAad(e);
      expect(envHeader(e).map((h) => h.toString('hex'))).toEqual(c.header_fields_hex);
      expect(aad.toString('hex')).toBe(c.aad_hex);
      const key = msgKey(hx(c.group_key_hex), ub(e.salt));
      expect(key.toString('hex')).toBe(c.msg_key_hex);
      expect(ub(e.ct).toString('hex')).toBe(c.ct_hex);
      expect(aeadSeal(key, aad, hx(c.plaintext_hex)).toString('hex')).toBe(c.ct_hex);
      const pt = aeadOpen(key, aad, ub(e.ct))!;
      expect(pt.toString('hex')).toBe(c.plaintext_hex);
      expect(pt.length).toBe(paddedLength(utf(c.payload_json_unpadded).length));
      expect(JSON.parse(pt.toString('utf8'))).toEqual(c.payload);
      const sigInput = cat(aad, lp(ub(e.ct)));
      expect(sigInput.toString('hex')).toBe(c.sig_input_hex);
      expect(edVerify(hx(c.sender_sign_pub_hex), sigInput, ub(e.sig))).toBe(true);
      expect(nodeSign(null, sigInput, edPriv(hx(c.sender_sign_sk_hex))).toString('hex')).toBe(c.sig_hex);
      expect(deviceId(hx(c.sender_sign_pub_hex))).toBe(e.from);
      if (c.collapse !== null) {
        const kc = hkdf(hx(c.group_key_hex), Buffer.alloc(0), 'carom/v1/collapse');
        expect(b64(hmac(kc, cat(lp(e.from), lp(c.collapse_real_key))).subarray(0, 16))).toBe(e.collapse);
      } else {
        expect(e.collapse).toBeUndefined();
      }
      expect(JSON.stringify(e)).toBe(c.envelope_json);
    }
  });

  it('envelope receive rules', () => {
    const TYPES_NEEDING_REPLY = new Set(['notification.dismiss@1']);
    for (const c of load('envelope_receive.json').cases) {
      const e = c.envelope;
      const x = c.context;
      const got = ((): string => {
        if (e.v !== 1 || !UUID7.test(e.msg_id) || !Array.isArray(e.to)) return 'bad_shape';
        if (!e.to.includes(x.self_id)) return 'not_addressed';
        const pubB64 = x.directory[e.from];
        if (pubB64 === undefined || deviceId(ub(pubB64)) !== e.from) return 'untrusted_sender';
        const aad = envAad(e);
        if (!edVerify(ub(pubB64), cat(aad, lp(ub(e.ct))), ub(e.sig))) return 'bad_signature';
        const k = x.keys[String(e.kv)];
        if (k === undefined) return 'unknown_kv';
        const pt = aeadOpen(msgKey(hx(k), ub(e.salt)), aad, ub(e.ct));
        if (pt === null) return 'decrypt_failed';
        let p: Json;
        try {
          p = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(pt));
        } catch {
          return 'bad_payload';
        }
        if (typeof p?.type !== 'string' || !Number.isSafeInteger(p.ts) || typeof p.body !== 'object') return 'bad_payload';
        if (x.now - p.ts > e.ttl_s * 1000 + 300_000) return 'expired';
        if (p.ts - x.now > 300_000) return 'from_future';
        if (x.seen_msg_ids.includes(e.msg_id)) return 'replay';
        if (!x.known_types.includes(p.type)) return 'unknown_type';
        if (TYPES_NEEDING_REPLY.has(p.type) && p.reply_to === undefined) return 'bad_payload';
        if (p.type === 'clipboard.set@1' && x.last_clipboard_ts !== null && p.ts < x.last_clipboard_ts) return 'stale_clipboard';
        if (p.reply_to !== undefined && !(x.original_recipients[p.reply_to] ?? []).includes(e.from)) return 'reply_not_allowed';
        return 'ok';
      })();
      expect(got, c.name).toBe(c.expected);
    }
  });

  it('device records', () => {
    const v = load('record.json');
    const s = v.seal;
    const aad = sealAad('carom/v1/rec', [utf(s.record.id), u64(s.record.updated)], s.kv, hx(s.salt_hex));
    expect(aad.toString('hex')).toBe(s.aad_hex);
    expect(msgKey(hx(s.group_key_hex), hx(s.salt_hex)).toString('hex')).toBe(s.msg_key_hex);
    const pt = aeadOpen(msgKey(hx(s.group_key_hex), hx(s.salt_hex)), aad, hx(s.ct_hex))!;
    expect(pt.toString('hex')).toBe(s.plaintext_hex);
    expect(JSON.parse(pt.toString('utf8'))).toEqual(s.record);
    expect(cat(aad, lp(hx(s.ct_hex))).toString('hex')).toBe(s.sig_input_hex);
    expect(edVerify(ub(s.record.sign_pub), hx(s.sig_input_hex), hx(s.sig_hex))).toBe(true);

    for (const c of v.open_cases) {
      const r = c.sealed;
      const x = c.context;
      const got = ((): string => {
        if (Object.keys(r).sort().join() !== 'ct,id,kv,salt,sig,updated') return 'bad_shape';
        if (r.kv < x.kv) return 'old_kv';
        if (r.kv > x.kv) return 'unknown_kv';
        const a = sealAad('carom/v1/rec', [utf(r.id), u64(r.updated)], r.kv, ub(r.salt));
        const p = aeadOpen(msgKey(hx(x.group_key_hex), ub(r.salt)), a, ub(r.ct));
        if (p === null) return 'decrypt_failed';
        const rec = JSON.parse(p.toString('utf8'));
        if (typeof rec.sign_pub !== 'string' || !Array.isArray(rec.caps)) return 'bad_record';
        if (rec.id !== r.id) return 'id_mismatch';
        if (rec.updated !== r.updated) return 'updated_mismatch';
        if (deviceId(ub(rec.sign_pub)) !== rec.id) return 'device_id_mismatch';
        if (!edVerify(ub(rec.sign_pub), cat(a, lp(ub(r.ct))), ub(r.sig))) return 'bad_signature';
        if (x.last_updated !== null && r.updated < x.last_updated) return 'rollback';
        return 'ok';
      })();
      expect(got, c.name).toBe(c.expected);
    }
  });

  it('box', () => {
    const v = load('box.json');
    for (const c of v.seal_cases) {
      const ephPub = rawPub(createPublicKey(xPriv(hx(c.eph_sk_hex))));
      expect(ephPub.toString('hex')).toBe(c.eph_pub_hex);
      expect(b64(ephPub)).toBe(c.box.epk);
      const ss = x25519(hx(c.eph_sk_hex), hx(c.rcpt_kx_pub_hex))!;
      expect(ss.toString('hex')).toBe(c.shared_secret_hex);
      const key = hkdf(cat(ss, hx(c.psk_hex)), cat(ephPub, hx(c.rcpt_kx_pub_hex)), 'carom/v1/box');
      expect(key.toString('hex')).toBe(c.box_key_hex);
      expect(aeadSeal(key, hx(c.aad_hex), hx(c.plaintext_hex)).toString('hex')).toBe(c.ct_hex);
      expect(b64(hx(c.ct_hex))).toBe(c.box.ct);
    }
    for (const c of v.open_cases) {
      const pt = boxOpen(c.box, hx(c.rcpt_kx_sk_hex), hx(c.aad_hex), hx(c.psk_hex));
      expect(pt === null ? null : pt.toString('hex'), c.name).toBe(c.expected_plaintext_hex);
    }
  });

  it('pairing', () => {
    const v = load('pairing.json');
    const kPair = hkdf(hx(v.pair_secret_hex), Buffer.alloc(0), 'carom/v1/pair');
    expect(kPair.toString('hex')).toBe(v.k_pair_hex);
    const body = v.request.accept_body;
    const input = cat(lp('carom/v1/pair-req'), lp(v.pair_id), lp(ub(body.sign_pub)), lp(ub(body.kx_pub)));
    expect(input.toString('hex')).toBe(v.request.request_input_hex);
    expect(hmac(kPair, input).toString('hex')).toBe(v.request.mac_hex);
    expect(ub(body.mac).toString('hex')).toBe(v.request.mac_hex);
    expect(edVerify(ub(body.sign_pub), input, ub(body.pop))).toBe(true);
    expect(nodeSign(null, input, edPriv(hx(v.joiner.sign_sk_hex))).toString('hex')).toBe(v.request.pop_hex);

    for (const c of v.candidate_cases) {
      const cand = c.candidate;
      const got = ((): string => {
        const sp = strictB64(cand.sign_pub);
        const kp = strictB64(cand.kx_pub);
        const mac = strictB64(cand.mac);
        if (!sp || sp.length !== 32 || !kp || kp.length !== 32 || !mac || mac.length !== 32) return 'bad_shape';
        const k = hkdf(hx(c.pair_secret_hex), Buffer.alloc(0), 'carom/v1/pair');
        const inp = cat(lp('carom/v1/pair-req'), lp(v.pair_id), lp(sp), lp(kp));
        if (!hmac(k, inp).equals(mac)) return 'bad_mac';
        if (cand.pop !== undefined && !edVerify(sp, inp, ub(cand.pop))) return 'bad_pop';
        if (deviceId(sp) !== cand.device_id) return 'device_id_mismatch';
        return 'ok';
      })();
      expect(got, c.name).toBe(c.expected);
    }

    const w = v.welcome;
    expect(cat(lp('carom/v1/welcome'), lp(v.pair_id), lp(v.joiner.device_id)).toString('hex')).toBe(w.aad_hex);
    expect(w.psk_hex).toBe(v.k_pair_hex);
    const pt = boxOpen(w.box, hx(v.joiner.kx_sk_hex), hx(w.aad_hex), kPair)!;
    expect(pt.toString('hex')).toBe(w.plaintext_hex);
    expect(JSON.parse(pt.toString('utf8'))).toEqual(w.welcome);

    for (const c of v.welcome_open_cases) {
      const aad = cat(lp('carom/v1/welcome'), lp(c.pair_id), lp(c.self_id));
      const k = hkdf(hx(c.pair_secret_hex), Buffer.alloc(0), 'carom/v1/pair');
      const opened = boxOpen(c.box, hx(v.joiner.kx_sk_hex), aad, k);
      expect(opened === null ? 'decrypt_failed' : 'ok', c.name).toBe(c.expected);
    }
  });

  it('rekey', () => {
    const v = load('rekey.json');
    const body = v.keyring_put_body;
    const ids = Object.keys(body.boxes).sort((a, b) => Buffer.compare(utf(a), utf(b)));
    const sigInput = (ver: Json): Buffer =>
      cat(
        lp('carom/v1/rekey'),
        lp(ver.by),
        u64(ver.kv),
        ...Object.keys(ver.boxes)
          .sort((a, b) => Buffer.compare(utf(a), utf(b)))
          .flatMap((id) => [lp(id), lp(ub(ver.boxes[id].epk)), lp(ub(ver.boxes[id].ct))]),
      );
    expect(sigInput(body).toString('hex')).toBe(v.sig_input_hex);
    expect(edVerify(hx(v.by.sign_pub_hex), sigInput(body), ub(body.sig))).toBe(true);
    expect(nodeSign(null, sigInput(body), edPriv(hx(v.by.sign_sk_hex))).toString('hex')).toBe(v.sig_hex);
    expect(v.boxes.map((b: Json) => b.device_id)).toEqual(ids);
    for (const b of v.boxes) {
      const aad = cat(lp('carom/v1/rekey'), lp(body.by), lp(b.device_id), lp(u64(body.kv)));
      expect(aad.toString('hex')).toBe(b.aad_hex);
      const m = v.members.find((x: Json) => x.device_id === b.device_id);
      expect(boxOpen(body.boxes[b.device_id], hx(m.kx_sk_hex), aad, Buffer.alloc(0))!.toString('hex')).toBe(v.new_group_key_hex);
    }

    for (const c of v.open_cases) {
      const ver = c.version;
      const got = ((): string => {
        if (typeof ver.boxes !== 'object' || Object.keys(ver.boxes).length === 0) return 'bad_shape';
        const pub = c.directory[ver.by];
        if (pub === undefined || deviceId(ub(pub)) !== ver.by) return 'untrusted_signer';
        if (!edVerify(ub(pub), sigInput(ver), ub(ver.sig))) return 'bad_signature';
        const box = ver.boxes[c.self_id];
        if (box === undefined) return 'no_box';
        const aad = cat(lp('carom/v1/rekey'), lp(ver.by), lp(c.self_id), lp(u64(ver.kv)));
        const k = boxOpen(box, hx(c.self_kx_sk_hex), aad, Buffer.alloc(0));
        if (k === null) return 'decrypt_failed';
        expect(k.toString('hex')).toBe(c.expected_group_key_hex);
        return 'ok';
      })();
      expect(got, c.name).toBe(c.expected);
    }
  });

  it('tokens', () => {
    const v = load('token.json');
    const key = hkdf(utf(v.setup_token), Buffer.alloc(0), 'carom/v1/token-key');
    expect(key.toString('hex')).toBe(v.token_key_hex);
    for (const c of v.make) {
      const input = cat(lp(c.device_id), lp(hx(c.rand_hex)));
      expect(input.toString('hex')).toBe(c.mac_input_hex);
      const mac = hmac(key, input).subarray(0, 16);
      expect(mac.toString('hex')).toBe(c.mac16_hex);
      expect(c.token).toBe(`ct1.${c.device_id}.${b64(hx(c.rand_hex))}.${b64(mac)}`);
      expect(sha256(utf(c.token)).toString('hex')).toBe(c.token_hash_hex);
    }
    for (const c of v.verify) {
      const [, id, rand, mac] = c.token.split('.');
      const k = hkdf(utf(c.setup_token), Buffer.alloc(0), 'carom/v1/token-key');
      const ok = hmac(k, cat(lp(id), lp(ub(rand)))).subarray(0, 16).equals(ub(mac));
      expect(ok ? id : null, c.name).toBe(c.expected_device_id);
    }
  });

  it('UUIDv7 layout', () => {
    const v = load('uuidv7.json');
    for (const c of v.format) {
      expect(c.uuid).toMatch(UUID7);
      expect(parseInt(c.uuid.slice(0, 8) + c.uuid.slice(9, 13), 16)).toBe(c.ms);
      expect(parseInt(c.uuid.slice(15, 18), 16)).toBe(c.counter);
      const randB = BigInt('0x' + c.rand_b_hex) & ((1n << 62n) - 1n);
      expect(BigInt('0x' + c.uuid.slice(19, 23) + c.uuid.slice(24)) & ((1n << 62n) - 1n)).toBe(randB);
    }
    const seq: string[] = v.generator_sequence.map((s: Json) => s.uuid);
    for (let i = 1; i < seq.length; i++) expect(seq[i]! > seq[i - 1]!).toBe(true);
    for (const c of v.invalid) expect(UUID7.test(c.value)).toBe(false);
  });
});
