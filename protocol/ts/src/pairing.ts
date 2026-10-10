// Pairing request MAC, proof of possession, and the welcome box (SPEC §8).

import { b64decodeLength, b64encode } from './b64.ts';
import { concat, equalBytes, fromUtf8, lp, utf8, type Bytes } from './bytes.ts';
import { TAG } from './constants.ts';
import { deviceIdMatches, isDeviceId, type Identity } from './device.ts';
import { openBox, sealBox, type Box } from './box.ts';
import { pairKey } from './kdf.ts';
import { ed25519Sign, ed25519Verify, hmacSha256, type Rng } from './primitives.ts';
import { isValidKv } from './seal.ts';

/** `lp("carom/v1/pair-req") || lp(pair_id) || lp(sign_pub) || lp(kx_pub)`: input to both mac and pop. */
export function pairRequestInput(pairId: string, signPub: Bytes, kxPub: Bytes): Bytes {
  return concat(lp(TAG.pairRequest), lp(pairId), lp(signPub), lp(kxPub));
}

/** `mac = HMAC-SHA256(k_pair, pair request input)`, full 32 bytes. */
export function pairRequestMac(kPair: Bytes, pairId: string, signPub: Bytes, kxPub: Bytes): Bytes {
  return hmacSha256(kPair, pairRequestInput(pairId, signPub, kxPub));
}

/** `pop = Ed25519(sign_sk, pair request input)`. */
export function signPairPop(signSk: Bytes, pairId: string, signPub: Bytes, kxPub: Bytes): Bytes {
  return ed25519Sign(signSk, pairRequestInput(pairId, signPub, kxPub));
}

export function verifyPairPop(pairId: string, signPub: Bytes, kxPub: Bytes, pop: Bytes): boolean {
  return ed25519Verify(signPub, pairRequestInput(pairId, signPub, kxPub), pop);
}

export interface PairAcceptBody {
  pair_id: string;
  sign_pub: string;
  kx_pub: string;
  mac: string;
  pop: string;
}

/** Body of `POST /v1/pair/accept`, built by the joining device B. */
export function buildPairAccept(pairId: string, pairSecret: Bytes, identity: Identity): PairAcceptBody {
  const { signSk, signPub, kxPub } = identity;
  return {
    pair_id: pairId,
    sign_pub: b64encode(signPub),
    kx_pub: b64encode(kxPub),
    mac: b64encode(pairRequestMac(pairKey(pairSecret), pairId, signPub, kxPub)),
    pop: b64encode(signPairPop(signSk, pairId, signPub, kxPub)),
  };
}

export interface PairCandidate {
  device_id: string;
  sign_pub: string;
  kx_pub: string;
  mac: string;
  /** Verified when present. The SPEC §6 candidate shape does not include it; the Hub checks it at accept. */
  pop?: string;
}

export type CandidateError = 'bad_shape' | 'bad_mac' | 'bad_pop' | 'device_id_mismatch';

export type CandidateResult =
  | { ok: true; deviceId: string; signPub: Bytes; kxPub: Bytes }
  | { ok: false; error: CandidateError };

/** Inviter A's check of a candidate (SPEC §8 step 3): mac, then pop, then device_id. */
export function verifyPairCandidate(pairId: string, pairSecret: Bytes, candidate: unknown): CandidateResult {
  if (typeof candidate !== 'object' || candidate === null) return { ok: false, error: 'bad_shape' };
  const c = candidate as Record<string, unknown>;
  const signPub = b64decodeLength(c.sign_pub, 32);
  const kxPub = b64decodeLength(c.kx_pub, 32);
  const mac = b64decodeLength(c.mac, 32);
  const pop = c.pop === undefined ? undefined : b64decodeLength(c.pop, 64);
  if (signPub === null || kxPub === null || mac === null || pop === null || typeof c.device_id !== 'string') {
    return { ok: false, error: 'bad_shape' };
  }
  const expected = pairRequestMac(pairKey(pairSecret), pairId, signPub, kxPub);
  if (!equalBytes(mac, expected)) return { ok: false, error: 'bad_mac' };
  if (pop !== undefined && !verifyPairPop(pairId, signPub, kxPub, pop)) return { ok: false, error: 'bad_pop' };
  if (!deviceIdMatches(c.device_id, signPub)) return { ok: false, error: 'device_id_mismatch' };
  return { ok: true, deviceId: c.device_id, signPub, kxPub };
}

export interface WelcomeKey {
  kv: number;
  /** b64 of the 32-byte group key. */
  k: string;
}

export interface Welcome {
  keys: WelcomeKey[];
  inviter: string;
}

/** `aad = lp("carom/v1/welcome") || lp(pair_id) || lp(B.id)` */
export function welcomeAad(pairId: string, candidateId: string): Bytes {
  return concat(lp(TAG.welcome), lp(pairId), lp(candidateId));
}

export function isValidWelcome(v: unknown): v is Welcome {
  if (typeof v !== 'object' || v === null || Array.isArray(v)) return false;
  const w = v as Record<string, unknown>;
  if (!isDeviceId(w.inviter) || !Array.isArray(w.keys) || w.keys.length === 0) return false;
  const seen = new Set<number>();
  for (const k of w.keys as unknown[]) {
    if (typeof k !== 'object' || k === null) return false;
    const { kv, k: key } = k as Record<string, unknown>;
    if (!isValidKv(kv) || seen.has(kv) || b64decodeLength(key, 32) === null) return false;
    seen.add(kv);
  }
  return true;
}

export function encodeWelcome(w: Welcome): Bytes {
  return utf8(JSON.stringify({ keys: w.keys.map(({ kv, k }) => ({ kv, k })), inviter: w.inviter }));
}

export interface SealWelcomeInput {
  pairId: string;
  candidateId: string;
  candidateKxPub: Bytes;
  pairSecret: Bytes;
  welcome: Welcome;
  ephSk?: Bytes;
  rng?: Rng;
}

/** `Box(rcpt=B.kx_pub, psk=k_pair, aad=welcomeAad, P=welcome JSON)` */
export function sealWelcome(input: SealWelcomeInput): Box {
  if (!isValidWelcome(input.welcome)) throw new RangeError('invalid welcome');
  return sealBox({
    rcptKxPub: input.candidateKxPub,
    aad: welcomeAad(input.pairId, input.candidateId),
    plaintext: encodeWelcome(input.welcome),
    psk: pairKey(input.pairSecret),
    ...(input.ephSk !== undefined ? { ephSk: input.ephSk } : {}),
    ...(input.rng !== undefined ? { rng: input.rng } : {}),
  });
}

export type WelcomeResult = { ok: true; welcome: Welcome } | { ok: false; error: 'decrypt_failed' | 'bad_welcome' };

/** Opened by the joining device B. */
export function openWelcome(box: unknown, pairId: string, selfId: string, kxSk: Bytes, pairSecret: Bytes): WelcomeResult {
  const plaintext = openBox(box, { kxSk, aad: welcomeAad(pairId, selfId), psk: pairKey(pairSecret) });
  if (plaintext === null) return { ok: false, error: 'decrypt_failed' };
  let value: unknown;
  try {
    value = JSON.parse(fromUtf8(plaintext));
  } catch {
    return { ok: false, error: 'bad_welcome' };
  }
  return isValidWelcome(value) ? { ok: true, welcome: value } : { ok: false, error: 'bad_welcome' };
}
