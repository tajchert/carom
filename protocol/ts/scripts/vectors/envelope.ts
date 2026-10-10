// Envelope seal, receive (positive and negative) and Hub shape vectors.

import { b64decode } from '../../src/b64.ts';
import { fromHex, utf8, type Bytes } from '../../src/bytes.ts';
import { collapseValue } from '../../src/collapse.ts';
import type { Identity } from '../../src/device.ts';
import { envelopeAad, envelopeHeaderFields, envelopeSigInput, sealEnvelope } from '../../src/envelope.ts';
import { validateEnvelopeShape, type Envelope } from '../../src/envelope-shape.ts';
import { msgKey } from '../../src/kdf.ts';
import { MESSAGE_TYPES } from '../../src/messages.ts';
import { encodePayload, type Payload } from '../../src/payload.ts';
import { receiveEnvelope, type ReceiveContext } from '../../src/receive.ts';
import {
  b64,
  hex,
  identity,
  K1,
  K2,
  LAPTOP,
  MAC,
  msgId,
  OUTSIDER,
  PHONE,
  seed,
  SIG_NOTE,
  T0,
  TABLET,
  type VectorFile,
} from './fixtures.ts';

interface SealSpec {
  name: string;
  sender: Identity;
  to: Identity[];
  groupKey: Bytes;
  kv: number;
  payload: Payload<unknown> | null;
  /** Raw plaintext instead of an encoded payload (negative cases). */
  rawPlaintext?: Bytes;
  collapseRealKey: string | null;
  ttlS: number;
  label: string;
}

function sealSpec(spec: SealSpec) {
  const plaintext = spec.rawPlaintext ?? encodePayload(spec.payload!);
  const salt = seed(`salt/${spec.label}`);
  const collapse = spec.collapseRealKey === null ? undefined : collapseValue(spec.groupKey, spec.sender.deviceId, spec.collapseRealKey);
  const env = sealEnvelope({
    msg_id: msgId(spec.label, spec.payload?.ts ?? T0),
    from: spec.sender.deviceId,
    to: spec.to.map((d) => d.deviceId),
    ...(collapse !== undefined ? { collapse } : {}),
    ttl_s: spec.ttlS,
    plaintext,
    groupKey: spec.groupKey,
    kv: spec.kv,
    signSk: spec.sender.signSk,
    salt,
  });
  return { env, plaintext, salt, collapse };
}

const NOTIF_KEY = '0|com.whatsapp|1|null|10123';

const posted: SealSpec = {
  name: 'notification.posted@1 from phone to mac and laptop',
  sender: PHONE,
  to: [MAC, LAPTOP],
  groupKey: K1,
  kv: 1,
  payload: {
    type: 'notification.posted@1',
    ts: T0,
    body: {
      key: NOTIF_KEY,
      app: { id: 'com.whatsapp', name: 'WhatsApp' },
      title: 'Anna',
      text: 'Dinner at 8? 🍝',
      category: 'msg',
      posted_at: T0 - 500,
      redacted: false,
    },
  },
  collapseRealKey: `n:${NOTIF_KEY}`,
  ttlS: 172_800,
  label: 'posted',
};

const removed: SealSpec = {
  name: 'notification.removed@1 sharing the collapse slot of the post',
  sender: PHONE,
  to: [MAC, LAPTOP],
  groupKey: K1,
  kv: 1,
  payload: { type: 'notification.removed@1', ts: T0 + 60_000, body: { key: NOTIF_KEY } },
  collapseRealKey: `n:${NOTIF_KEY}`,
  ttlS: 172_800,
  label: 'removed',
};

const clipboard: SealSpec = {
  name: 'clipboard.set@1 from mac to laptop and phone (to kept in sent order)',
  sender: MAC,
  to: [LAPTOP, PHONE],
  groupKey: K1,
  kv: 1,
  payload: {
    type: 'clipboard.set@1',
    ts: T0 + 1_234,
    body: { mime: 'text/plain', text: 'Zażółć gęślą jaźń — "quoted" \\ backslash', sensitive: false },
  },
  collapseRealKey: 'clip',
  ttlS: 86_400,
  label: 'clipboard',
};

const dismissReply: SealSpec = {
  name: 'notification.dismiss@1 reply from mac to the phone (reply_to = posted msg_id, no collapse)',
  sender: MAC,
  to: [PHONE],
  groupKey: K1,
  kv: 1,
  payload: {
    type: 'notification.dismiss@1',
    ts: T0 + 5_000,
    reply_to: msgId('posted', T0),
    body: { key: NOTIF_KEY },
  },
  collapseRealKey: null,
  ttlS: 86_400,
  label: 'dismiss',
};

const status: SealSpec = {
  name: 'device.status@1 under kv 2',
  sender: PHONE,
  to: [MAC],
  groupKey: K2,
  kv: 2,
  payload: { type: 'device.status@1', ts: T0 + 9_000, body: { battery: 87, charging: true } },
  collapseRealKey: 'status',
  ttlS: 86_400,
  label: 'status',
};

export function envelopeSealVectors(): VectorFile {
  return {
    description:
      'Envelope seal (SPEC §2.3, §3). tag = "carom/v1/env"; H = [u64(v), msg_id, from, to_joined, collapse_or_empty, u64(ttl_s)] with to_joined = lp(to[0]) || lp(to[1]) || ...; aad = lp(tag) || lp(H[0]) || ... || lp(u64(kv)) || lp(salt); ct = ChaCha20-Poly1305(msg_key(K, salt), nonce = 12 zero bytes, aad, plaintext); sig = Ed25519(sign_sk, aad || lp(ct)). Payload JSON is padded with spaces (SPEC §3.1); plaintext_hex is the exact padded plaintext. Opening with sender_sign_pub and group_key MUST yield plaintext_hex. ' +
      SIG_NOTE,
    cases: [posted, removed, clipboard, dismissReply, status].map((spec) => {
      const { env, plaintext, salt, collapse } = sealSpec(spec);
      return {
        name: spec.name,
        sender: spec.sender.deviceId,
        sender_sign_sk_hex: hex(spec.sender.signSk),
        sender_sign_pub_hex: hex(spec.sender.signPub),
        group_key_hex: hex(spec.groupKey),
        kv: spec.kv,
        payload: spec.payload,
        payload_json_unpadded: JSON.stringify(spec.payload),
        plaintext_length: plaintext.length,
        plaintext_hex: hex(plaintext),
        collapse_real_key: spec.collapseRealKey,
        collapse: collapse ?? null,
        salt_hex: hex(salt),
        msg_key_hex: hex(msgKey(spec.groupKey, salt)),
        header_fields_hex: envelopeHeaderFields(env).map(hex),
        aad_hex: hex(envelopeAad(env)),
        ct_hex: hex(b64decode(env.ct)),
        sig_input_hex: hex(envelopeSigInput(env)),
        sig_hex: hex(b64decode(env.sig)),
        sig_kind: 'deterministic_rfc8032',
        envelope: env,
        envelope_json: JSON.stringify(env),
      };
    }),
  };
}

interface ContextJson {
  self_id: string;
  now: number;
  directory: Record<string, string>;
  keys: Record<string, string>;
  seen_msg_ids: string[];
  known_types: string[];
  last_clipboard_ts: number | null;
  original_recipients: Record<string, string[]>;
}

function directoryOf(...ids: Identity[]): Record<string, string> {
  return Object.fromEntries(ids.map((d) => [d.deviceId, b64(d.signPub)]));
}

function baseContext(self: Identity, now: number): ContextJson {
  return {
    self_id: self.deviceId,
    now,
    directory: directoryOf(PHONE, MAC, LAPTOP, TABLET),
    keys: { '1': hex(K1) },
    seen_msg_ids: [],
    known_types: [...MESSAGE_TYPES],
    last_clipboard_ts: null,
    original_recipients: {},
  };
}

/** Converts the JSON context into the TypeScript receive context. */
export function contextFromJson(c: ContextJson): ReceiveContext {
  return {
    selfId: c.self_id,
    now: c.now,
    directory: new Map(Object.entries(c.directory).map(([id, pub]) => [id, b64decode(pub)])),
    keys: new Map(Object.entries(c.keys).map(([kv, k]) => [Number(kv), fromHex(k)])),
    hasSeen: (id) => c.seen_msg_ids.includes(id),
    knownTypes: new Set(c.known_types),
    ...(c.last_clipboard_ts !== null ? { lastClipboardTs: c.last_clipboard_ts } : {}),
    originalRecipients: (id) => (Object.hasOwn(c.original_recipients, id) ? c.original_recipients[id] : undefined),
  };
}

function flipFirstBit(field: string): string {
  const b = b64decode(field);
  b[0]! ^= 0x01;
  return b64(b);
}

export function envelopeReceiveVectors(): VectorFile {
  const clip: SealSpec = {
    name: 'base',
    sender: PHONE,
    to: [MAC, LAPTOP],
    groupKey: K1,
    kv: 1,
    payload: { type: 'clipboard.set@1', ts: T0, body: { mime: 'text/plain', text: 'hello', sensitive: false } },
    collapseRealKey: 'clip',
    ttlS: 86_400,
    label: 'recv-base',
  };
  const base = sealSpec(clip).env;
  const ts = T0;
  const ttlMs = clip.ttlS * 1000;
  const ctx = (overrides: Partial<ContextJson> = {}): ContextJson => ({ ...baseContext(MAC, T0 + 1_000), ...overrides });
  const mutate = (patch: Partial<Envelope>, drop?: keyof Envelope): Envelope => {
    const e: Record<string, unknown> = { ...base, ...patch };
    if (drop !== undefined) delete e[drop];
    return e as unknown as Envelope;
  };
  const sealed = (label: string, patch: Partial<SealSpec>) => sealSpec({ ...clip, label, ...patch }).env;

  const originalMsgId = msgId('posted', T0);
  const replyFrom = (sender: Identity, label: string) =>
    sealed(label, {
      sender,
      to: [PHONE],
      payload: { type: 'notification.dismiss@1', ts: T0 + 5_000, reply_to: originalMsgId, body: { key: NOTIF_KEY } },
      collapseRealKey: null,
    });

  const cases: Array<{ name: string; envelope: Envelope; context: ContextJson; expected: string }> = [
    { name: 'valid clipboard.set', envelope: base, context: ctx(), expected: 'ok' },
    { name: 'v changed to 2', envelope: mutate({ v: 2 as 1 }), context: ctx(), expected: 'bad_shape' },
    { name: 'msg_id replaced', envelope: mutate({ msg_id: msgId('other', T0) }), context: ctx(), expected: 'bad_signature' },
    { name: 'from replaced by another trusted device', envelope: mutate({ from: TABLET.deviceId }), context: ctx(), expected: 'bad_signature' },
    { name: 'to reordered', envelope: mutate({ to: [LAPTOP.deviceId, MAC.deviceId] }), context: ctx(), expected: 'bad_signature' },
    { name: 'to with a recipient removed', envelope: mutate({ to: [MAC.deviceId] }), context: ctx(), expected: 'bad_signature' },
    {
      name: 'to with a recipient added',
      envelope: mutate({ to: [MAC.deviceId, LAPTOP.deviceId, TABLET.deviceId] }),
      context: ctx(),
      expected: 'bad_signature',
    },
    { name: 'collapse replaced', envelope: mutate({ collapse: collapseValue(K1, PHONE.deviceId, 'status') }), context: ctx(), expected: 'bad_signature' },
    { name: 'collapse removed', envelope: mutate({}, 'collapse'), context: ctx(), expected: 'bad_signature' },
    { name: 'ttl_s changed', envelope: mutate({ ttl_s: 172_800 }), context: ctx(), expected: 'bad_signature' },
    { name: 'kv changed to 2 (receiver knows kv 2)', envelope: mutate({ kv: 2 }), context: ctx({ keys: { '1': hex(K1), '2': hex(K2) } }), expected: 'bad_signature' },
    { name: 'salt replaced', envelope: mutate({ salt: b64(seed('salt/other')) }), context: ctx(), expected: 'bad_signature' },
    { name: 'ct first bit flipped', envelope: mutate({ ct: flipFirstBit(base.ct) }), context: ctx(), expected: 'bad_signature' },
    { name: 'sig first bit flipped', envelope: mutate({ sig: flipFirstBit(base.sig) }), context: ctx(), expected: 'bad_signature' },
    {
      name: 'sealed under kv 2, receiver only has kv 1',
      envelope: sealed('recv-kv2', { groupKey: K2, kv: 2 }),
      context: ctx(),
      expected: 'unknown_kv',
    },
    { name: 'receiver holds a different key for kv 1', envelope: base, context: ctx({ keys: { '1': hex(K2) } }), expected: 'decrypt_failed' },
    {
      name: 'sender not in the trusted directory',
      envelope: sealed('recv-outsider', { sender: OUTSIDER }),
      context: ctx(),
      expected: 'untrusted_sender',
    },
    {
      name: 'directory sign_pub does not derive the sender id',
      envelope: base,
      context: ctx({ directory: { ...directoryOf(MAC, LAPTOP, TABLET), [PHONE.deviceId]: b64(LAPTOP.signPub) } }),
      expected: 'untrusted_sender',
    },
    { name: 'receiver not in to', envelope: base, context: { ...baseContext(TABLET, T0 + 1_000) }, expected: 'not_addressed' },
    { name: 'ts - now == 300000 (accepted)', envelope: base, context: ctx({ now: ts - 300_000 }), expected: 'ok' },
    { name: 'ts - now == 300001', envelope: base, context: ctx({ now: ts - 300_001 }), expected: 'from_future' },
    { name: 'now - ts == ttl_s*1000 + 300000 (accepted)', envelope: base, context: ctx({ now: ts + ttlMs + 300_000 }), expected: 'ok' },
    { name: 'now - ts == ttl_s*1000 + 300001', envelope: base, context: ctx({ now: ts + ttlMs + 300_001 }), expected: 'expired' },
    { name: 'msg_id already seen', envelope: base, context: ctx({ seen_msg_ids: [base.msg_id] }), expected: 'replay' },
    {
      name: 'plaintext is not JSON',
      envelope: sealed('recv-garbage', { payload: null, rawPlaintext: utf8('not json') }),
      context: ctx(),
      expected: 'bad_payload',
    },
    {
      name: 'unknown type',
      envelope: sealed('recv-unknown', { payload: { type: 'example.future@1', ts: T0, body: {} }, collapseRealKey: null }),
      context: ctx(),
      expected: 'unknown_type',
    },
    { name: 'type not in the receiver caps', envelope: base, context: ctx({ known_types: ['notification.posted@1'] }), expected: 'unknown_type' },
    { name: 'clipboard older than the last applied', envelope: base, context: ctx({ last_clipboard_ts: ts + 1 }), expected: 'stale_clipboard' },
    { name: 'clipboard with ts equal to the last applied', envelope: base, context: ctx({ last_clipboard_ts: ts }), expected: 'ok' },
    {
      name: 'reply from a recipient of the original',
      envelope: replyFrom(MAC, 'recv-reply-ok'),
      context: { ...baseContext(PHONE, T0 + 6_000), original_recipients: { [originalMsgId]: [MAC.deviceId, LAPTOP.deviceId] } },
      expected: 'ok',
    },
    {
      name: 'reply from a device that was not a recipient of the original',
      envelope: replyFrom(TABLET, 'recv-reply-bad'),
      context: { ...baseContext(PHONE, T0 + 6_000), original_recipients: { [originalMsgId]: [MAC.deviceId, LAPTOP.deviceId] } },
      expected: 'reply_not_allowed',
    },
    {
      name: 'reply to an unknown original message',
      envelope: replyFrom(MAC, 'recv-reply-ok'),
      context: { ...baseContext(PHONE, T0 + 6_000) },
      expected: 'reply_not_allowed',
    },
  ];

  for (const c of cases) {
    const r = receiveEnvelope(c.envelope, contextFromJson(c.context));
    const got = r.ok ? 'ok' : r.error;
    if (got !== c.expected) throw new Error(`receive vector "${c.name}": expected ${c.expected}, got ${got}`);
  }

  return {
    description:
      'Receiver rules (SPEC §3.3) applied in order: shape (bad_shape); 1. own id in to (not_addressed), from in the trusted directory with device_id(sign_pub) == from (untrusted_sender); 2. signature (bad_signature), kv known (unknown_kv), decryption (decrypt_failed), payload JSON wrapper {type, ts, reply_to?, body} (bad_payload); 3. now - ts <= ttl_s*1000 + 300000 (expired) and ts - now <= 300000 (from_future), both inclusive; 4. msg_id not seen (replay); 5. type known / in caps (unknown_type, acknowledged and ignored); 6. clipboard.set older than the last applied (stale_clipboard, equal ts is accepted); 7. reply_to only from a recipient of the original (reply_not_allowed). context.directory maps device id to b64 sign_pub, context.keys maps kv to group key hex. expected is "ok" or the first failing error code. Every failure is dropped silently and still acknowledged.',
    cases,
  };
}

function deviceIds(count: number): string[] {
  return Array.from({ length: count }, (_, i) => identity(`many-${i}`).deviceId);
}

export function envelopeShapeVectors(): VectorFile {
  const base = sealSpec({
    name: 'shape-base',
    sender: PHONE,
    to: [MAC, LAPTOP],
    groupKey: K1,
    kv: 1,
    payload: { type: 'clipboard.set@1', ts: T0, body: { mime: 'text/plain', text: 'hi', sensitive: false } },
    collapseRealKey: 'clip',
    ttlS: 86_400,
    label: 'shape-base',
  }).env;
  const e = (patch: Record<string, unknown>, drop?: string): Record<string, unknown> => {
    const out: Record<string, unknown> = { ...base, ...patch };
    if (drop !== undefined) delete out[drop];
    return out;
  };
  const cases: Array<{ name: string; envelope: Record<string, unknown>; serialized_bytes?: number; expected: string }> = [
    { name: 'valid', envelope: e({}), expected: 'ok' },
    { name: 'valid without collapse', envelope: e({}, 'collapse'), expected: 'ok' },
    { name: 'ttl_s = 60', envelope: e({ ttl_s: 60 }), expected: 'ok' },
    { name: 'ttl_s = 604800', envelope: e({ ttl_s: 604_800 }), expected: 'ok' },
    { name: '32 recipients', envelope: e({ to: deviceIds(32) }), expected: 'ok' },
    { name: 'serialized size exactly 98304', envelope: e({}), serialized_bytes: 98_304, expected: 'ok' },
    { name: 'v = 2', envelope: e({ v: 2 }), expected: 'bad_request' },
    { name: 'v as string', envelope: e({ v: '1' }), expected: 'bad_request' },
    { name: 'msg_id uppercase', envelope: e({ msg_id: base.msg_id.toUpperCase() }), expected: 'bad_request' },
    { name: 'msg_id is UUIDv4', envelope: e({ msg_id: '0192a4b1-7c3d-4e5f-8a9b-0c1d2e3f4a5b' }), expected: 'bad_request' },
    { name: 'msg_id without hyphens', envelope: e({ msg_id: base.msg_id.replaceAll('-', '') }), expected: 'bad_request' },
    { name: 'from not a device id', envelope: e({ from: 'dev_short' }), expected: 'bad_request' },
    { name: 'to empty', envelope: e({ to: [] }), expected: 'bad_request' },
    { name: '33 recipients', envelope: e({ to: deviceIds(33) }), expected: 'bad_request' },
    { name: 'to with duplicates', envelope: e({ to: [MAC.deviceId, MAC.deviceId] }), expected: 'bad_request' },
    { name: 'to contains from', envelope: e({ to: [MAC.deviceId, PHONE.deviceId] }), expected: 'bad_request' },
    { name: 'to not an array', envelope: e({ to: MAC.deviceId }), expected: 'bad_request' },
    { name: 'collapse empty string', envelope: e({ collapse: '' }), expected: 'bad_request' },
    { name: 'collapse 15 bytes', envelope: e({ collapse: b64(new Uint8Array(15)) }), expected: 'bad_request' },
    { name: 'ttl_s = 59', envelope: e({ ttl_s: 59 }), expected: 'bad_request' },
    { name: 'ttl_s = 604801', envelope: e({ ttl_s: 604_801 }), expected: 'bad_request' },
    { name: 'ttl_s not an integer', envelope: e({ ttl_s: 86_400.5 }), expected: 'bad_request' },
    { name: 'kv = 0', envelope: e({ kv: 0 }), expected: 'bad_request' },
    { name: 'kv = 2^32', envelope: e({ kv: 4_294_967_296 }), expected: 'bad_request' },
    { name: 'salt 31 bytes', envelope: e({ salt: b64(new Uint8Array(31)) }), expected: 'bad_request' },
    { name: 'salt with padding', envelope: e({ salt: base.salt + '=' }), expected: 'bad_request' },
    { name: 'sig 63 bytes', envelope: e({ sig: b64(new Uint8Array(63)) }), expected: 'bad_request' },
    { name: 'ct shorter than the 16-byte tag', envelope: e({ ct: b64(new Uint8Array(15)) }), expected: 'bad_request' },
    { name: 'ct uses the standard base64 alphabet', envelope: e({ ct: '+' + base.ct.slice(1) }), expected: 'bad_request' },
    { name: 'unknown field', envelope: e({ extra: 1 }), expected: 'bad_request' },
    { name: 'missing sig', envelope: e({}, 'sig'), expected: 'bad_request' },
    { name: 'ct longer than 65536 + 16 bytes', envelope: e({ ct: b64(new Uint8Array(65_553)) }), expected: 'too_large' },
    { name: 'serialized size 98305', envelope: e({}), serialized_bytes: 98_305, expected: 'too_large' },
  ];
  for (const c of cases) {
    const r = validateEnvelopeShape(c.envelope, c.serialized_bytes);
    const got = r.ok ? 'ok' : r.code;
    if (got !== c.expected) throw new Error(`shape vector "${c.name}": expected ${c.expected}, got ${got}`);
  }
  return {
    description:
      'Hub-side structural checks on POST /v1/messages (SPEC §3, §6 rule 1). No keys are needed. expected is "ok" or the Hub error code: bad_request (400) or too_large (413). serialized_bytes, when present, is the UTF-8 byte length of the request envelope JSON to check against the 98304-byte limit. The envelope is strict: unknown fields are rejected; b64 fields must decode canonically to their exact lengths (salt 32, sig 64, collapse 16, ct 16..65552).',
    cases,
  };
}
