// Envelope validation and signature input (SPEC §3).

import { verifyEd25519 } from "./crypto";
import { concat, lp, u64, utf8 } from "./encoding";
import { HttpError, badRequest } from "./errors";
import { DEVICE_ID, UUID_V7 } from "./ids";
import { sealAad, sealSigInput } from "./seal";
import { expectB64, expectInt, expectKeys, expectObject, expectString } from "./validate";

export const ENVELOPE_TAG = "carom/v1/env";
export const MAX_ENVELOPE_BYTES = 98_304;
export const MAX_PLAINTEXT_BYTES = 65_536;
export const MIN_TTL_S = 60;
export const MAX_TTL_S = 604_800;
export const MAX_RECIPIENTS = 32;
const AEAD_TAG_BYTES = 16;
const U32_MAX = 0xffff_ffff;

export interface Envelope {
  v: 1;
  msg_id: string;
  from: string;
  to: string[];
  collapse?: string;
  ttl_s: number;
  kv: number;
  salt: string;
  ct: string;
  sig: string;
}

export interface ParsedEnvelope {
  envelope: Envelope;
  /** The envelope as stored and delivered (JSON, at most MAX_ENVELOPE_BYTES). */
  serialized: string;
  salt: Uint8Array;
  ct: Uint8Array;
  sig: Uint8Array;
}

const REQUIRED = ["v", "msg_id", "from", "to", "ttl_s", "kv", "salt", "ct", "sig"] as const;

/** Strict shape and size check (rule 1 of SPEC §6 POST /v1/messages). */
export function parseEnvelope(raw: unknown): ParsedEnvelope {
  const obj = expectObject(raw, "envelope");
  expectKeys(obj, "envelope", REQUIRED, ["collapse"]);
  if (obj.v !== 1) throw badRequest("envelope.v must be 1");
  const msgId = expectString(obj.msg_id, "envelope.msg_id", UUID_V7);
  const from = expectString(obj.from, "envelope.from", DEVICE_ID);
  if (!Array.isArray(obj.to) || obj.to.length < 1 || obj.to.length > MAX_RECIPIENTS) {
    throw badRequest(`envelope.to must list 1..${MAX_RECIPIENTS} device ids`);
  }
  const to = obj.to.map((id, i) => expectString(id, `envelope.to[${i}]`, DEVICE_ID));
  if (new Set(to).size !== to.length) throw badRequest("envelope.to has duplicates");
  if (to.includes(from)) throw badRequest("envelope.to must not include the sender");
  let collapse: string | undefined;
  if (obj.collapse !== undefined) {
    expectB64(obj.collapse, "envelope.collapse", 16);
    collapse = obj.collapse as string;
  }
  const ttl = expectInt(obj.ttl_s, "envelope.ttl_s", MIN_TTL_S, MAX_TTL_S);
  const kv = expectInt(obj.kv, "envelope.kv", 1, U32_MAX);
  const salt = expectB64(obj.salt, "envelope.salt", 32);
  const ct = expectB64(obj.ct, "envelope.ct", [AEAD_TAG_BYTES, Number.MAX_SAFE_INTEGER]);
  if (ct.length > MAX_PLAINTEXT_BYTES + AEAD_TAG_BYTES) {
    throw new HttpError("too_large", `payload exceeds ${MAX_PLAINTEXT_BYTES} bytes`);
  }
  const sig = expectB64(obj.sig, "envelope.sig", 64);

  const envelope: Envelope = {
    v: 1,
    msg_id: msgId,
    from,
    to,
    ...(collapse === undefined ? {} : { collapse }),
    ttl_s: ttl,
    kv,
    salt: obj.salt as string,
    ct: obj.ct as string,
    sig: obj.sig as string,
  };
  const serialized = JSON.stringify(envelope);
  if (utf8(serialized).length > MAX_ENVELOPE_BYTES) {
    throw new HttpError("too_large", `envelope exceeds ${MAX_ENVELOPE_BYTES} bytes`);
  }
  return { envelope, serialized, salt, ct, sig };
}

/** H = [u64(v), msg_id, from, to_joined, collapse_or_empty, u64(ttl_s)] with tag "carom/v1/env". */
export function envelopeSigInput(env: Envelope, salt: Uint8Array, ct: Uint8Array): Uint8Array {
  const toJoined = concat(...env.to.map((id) => lp(id)));
  const headers = [u64(env.v), utf8(env.msg_id), utf8(env.from), toJoined, utf8(env.collapse ?? ""), u64(env.ttl_s)];
  return sealSigInput(sealAad(ENVELOPE_TAG, headers, env.kv, salt), ct);
}

export function verifyEnvelope(parsed: ParsedEnvelope, signPub: Uint8Array): Promise<boolean> {
  return verifyEd25519(signPub, parsed.sig, envelopeSigInput(parsed.envelope, parsed.salt, parsed.ct));
}
