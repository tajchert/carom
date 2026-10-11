import { describe, expect, it } from 'vitest';
import { b64encode } from '../src/b64.ts';
import { fromUtf8, utf8 } from '../src/bytes.ts';
import { generateIdentity } from '../src/device.ts';
import { openEnvelope, sealEnvelope, verifyEnvelopeSignature, type SealEnvelopeInput } from '../src/envelope.ts';
import { validateEnvelopeShape } from '../src/envelope-shape.ts';
import { decodePayload, encodePayload, paddedLength, padPayload } from '../src/payload.ts';
import { checkFreshness, isReplyAllowed, isStaleClipboard, receiveEnvelope, trustedSenderKey, type ReceiveContext } from '../src/receive.ts';
import { createUuidV7Generator } from '../src/uuidv7.ts';

const rngFrom = (seed: number) => {
  let i = seed;
  return (n: number) => Uint8Array.from({ length: n }, () => (i = (i * 1103515245 + 12345) & 0xff));
};
const A = generateIdentity(rngFrom(1));
const B = generateIdentity(rngFrom(2));
const C = generateIdentity(rngFrom(3));
const K = new Uint8Array(32).fill(5);
const NOW = 1_791_648_000_000;
const nextId = createUuidV7Generator({ now: () => NOW, rng: rngFrom(4) });

function make(payload: Parameters<typeof encodePayload>[0], patch: Partial<SealEnvelopeInput> = {}) {
  return sealEnvelope({
    msg_id: nextId(),
    from: A.deviceId,
    to: [B.deviceId],
    ttl_s: 86_400,
    plaintext: encodePayload(payload),
    groupKey: K,
    kv: 1,
    signSk: A.signSk,
    rng: rngFrom(6),
    ...patch,
  });
}

const clip = { type: 'clipboard.set@1', ts: NOW, body: { mime: 'text/plain', text: 'x', sensitive: false } };
const keys = new Map([[1, K]]);

function ctx(patch: Partial<ReceiveContext> = {}): ReceiveContext {
  return {
    selfId: B.deviceId,
    directory: new Map([
      [A.deviceId, A.signPub],
      [B.deviceId, B.signPub],
      [C.deviceId, C.signPub],
    ]),
    keys,
    now: NOW,
    hasSeen: () => false,
    ...patch,
  };
}

describe('payload', () => {
  it('serializes type, ts, reply_to, body in order and pads with spaces', () => {
    const bytes = encodePayload({ body: { a: 1 }, reply_to: '01928f3c-7b1e-7c4a-9d2f-5e8a1b2c3d4e', ts: 1, type: 't' });
    expect(bytes.length).toBe(256);
    expect(fromUtf8(bytes).trimEnd()).toBe('{"type":"t","ts":1,"reply_to":"01928f3c-7b1e-7c4a-9d2f-5e8a1b2c3d4e","body":{"a":1}}');
    expect(fromUtf8(bytes).endsWith(' ')).toBe(true);
    expect(encodePayload({ type: 't', ts: 1, body: {} }, { pad: false }).length).toBe(29);
  });

  it('enforces the plaintext limit', () => {
    const big = { type: 't', ts: 1, body: { s: 'x'.repeat(65_536) } };
    expect(() => encodePayload(big)).toThrow(RangeError);
    expect(() => encodePayload(big, { pad: false })).toThrow(RangeError);
    expect(() => paddedLength(-1)).toThrow(RangeError);
    expect(padPayload(new Uint8Array(4097)).length).toBe(8192);
  });

  it.each([
    ['not JSON', utf8('nope')],
    ['invalid UTF-8', Uint8Array.from([0x7b, 0xff, 0x7d])],
    ['array', utf8('[]')],
    ['empty type', utf8('{"type":"","ts":1,"body":{}}')],
    ['fractional ts', utf8('{"type":"t","ts":1.5,"body":{}}')],
    ['negative ts', utf8('{"type":"t","ts":-1,"body":{}}')],
    ['bad reply_to', utf8('{"type":"t","ts":1,"reply_to":"x","body":{}}')],
    ['array body', utf8('{"type":"t","ts":1,"body":[]}')],
    ['missing body', utf8('{"type":"t","ts":1}')],
  ])('decodePayload rejects %s', (_, bytes) => {
    expect(decodePayload(bytes)).toBeNull();
  });
});

describe('sealEnvelope / openEnvelope', () => {
  it('round-trips and omits collapse when absent', () => {
    const env = make(clip);
    expect('collapse' in env).toBe(false);
    expect(Object.keys(env)).toEqual(['v', 'msg_id', 'from', 'to', 'ttl_s', 'kv', 'salt', 'ct', 'sig']);
    const r = openEnvelope(env, A.signPub, keys);
    expect(r.ok && r.payload).toEqual(clip);
    expect(verifyEnvelopeSignature(env, A.signPub)).toBe(true);
    expect(verifyEnvelopeSignature(env, B.signPub)).toBe(false);
    expect(verifyEnvelopeSignature({ ...env, salt: '!' }, A.signPub)).toBe(false);
  });

  it('throws on invalid input instead of producing a bad envelope', () => {
    expect(() => make(clip, { to: [A.deviceId] })).toThrow(/to must not contain from/);
    expect(() => make(clip, { ttl_s: 59 })).toThrow(RangeError);
    expect(() => make(clip, { plaintext: new Uint8Array(65_537) })).toThrow(RangeError);
    expect(() => make(clip, { msg_id: 'nope' })).toThrow(RangeError);
  });

  it('reports each failure stage', () => {
    const env = make(clip);
    expect(openEnvelope({ ...env, v: 2 }, A.signPub, keys)).toEqual({ ok: false, error: 'bad_shape' });
    expect(openEnvelope(env, B.signPub, keys)).toEqual({ ok: false, error: 'bad_signature' });
    expect(openEnvelope(env, A.signPub, new Map())).toEqual({ ok: false, error: 'unknown_kv' });
    expect(openEnvelope(env, A.signPub, new Map([[1, new Uint8Array(32)]]))).toEqual({ ok: false, error: 'decrypt_failed' });
    const garbage = make(clip, { plaintext: utf8('{}') });
    expect(openEnvelope(garbage, A.signPub, keys)).toEqual({ ok: false, error: 'bad_payload' });
  });
});

describe('validateEnvelopeShape', () => {
  const env = make(clip);
  it.each([null, [], 'x', 1])('rejects non-objects: %j', (v) => {
    expect(validateEnvelopeShape(v)).toMatchObject({ ok: false, code: 'bad_request' });
  });
  it('reports the field', () => {
    expect(validateEnvelopeShape({ ...env, to: [5] })).toMatchObject({ ok: false, message: 'to must contain device ids' });
    expect(validateEnvelopeShape({ ...env, salt: 5 })).toMatchObject({ ok: false, message: 'salt must be a string' });
    expect(validateEnvelopeShape({ ...env, ct: b64encode(new Uint8Array(65_552)) }).ok).toBe(true);
  });
});

describe('receiver rules', () => {
  it('pure helpers', () => {
    expect(checkFreshness(0, 60, 60_000 + 300_000)).toBeNull();
    expect(checkFreshness(0, 60, 60_000 + 300_001)).toBe('expired');
    expect(checkFreshness(300_000, 60, 0)).toBeNull();
    expect(checkFreshness(300_001, 60, 0)).toBe('from_future');
    expect(isStaleClipboard(5, undefined)).toBe(false);
    expect(isStaleClipboard(5, 5)).toBe(false);
    expect(isStaleClipboard(4, 5)).toBe(true);
    expect(isReplyAllowed('a', undefined)).toBe(false);
    expect(isReplyAllowed('a', ['b'])).toBe(false);
    expect(isReplyAllowed('a', ['a'])).toBe(true);
    expect(trustedSenderKey(new Map([[A.deviceId, A.signPub]]), A.deviceId)).toBe(A.signPub);
    expect(trustedSenderKey(new Map([[A.deviceId, B.signPub]]), A.deviceId)).toBeNull();
    expect(trustedSenderKey(new Map(), A.deviceId)).toBeNull();
  });

  it('accepts a valid message and returns the payload', () => {
    const r = receiveEnvelope(make(clip), ctx());
    expect(r.ok && r.payload.body).toEqual(clip.body);
  });

  it('rejects malformed known bodies and a dismiss without reply_to', () => {
    expect(receiveEnvelope(make({ ...clip, body: { mime: 'text/html' } }), ctx())).toEqual({ ok: false, error: 'bad_payload' });
    const dismiss = { type: 'notification.dismiss@1', ts: NOW, body: { key: 'k' } };
    expect(receiveEnvelope(make(dismiss), ctx())).toEqual({ ok: false, error: 'bad_payload' });
  });

  it('rejects an action, action result or media command without reply_to', () => {
    const bodies: Array<[string, Record<string, unknown>]> = [
      ['notification.action@1', { key: 'k', action: 'tap' }],
      ['notification.action_result@1', { ok: true }],
      ['media.command@1', { session: 's', command: 'play' }],
    ];
    for (const [type, body] of bodies) {
      expect(receiveEnvelope(make({ type, ts: NOW, body }), ctx()), type).toEqual({ ok: false, error: 'bad_payload' });
    }
    // media.state is not a reply.
    const state = { type: 'media.state@1', ts: NOW, body: { session: 's', app: { id: 'a', name: 'A' }, state: 'paused', controls: [] } };
    expect(receiveEnvelope(make(state), ctx()).ok).toBe(true);
  });

  it('accepts unknown types the receiver declares in knownTypes', () => {
    const custom = { type: 'example.custom@1', ts: NOW, body: { any: 1 } };
    expect(receiveEnvelope(make(custom), ctx()).ok).toBe(false);
    expect(receiveEnvelope(make(custom), ctx({ knownTypes: new Set(['example.custom@1']) })).ok).toBe(true);
  });

  it('checks replies against the original recipients', () => {
    const original = nextId();
    const reply = make({ type: 'notification.dismiss@1', ts: NOW, reply_to: original, body: { key: 'k' } });
    expect(receiveEnvelope(reply, ctx({ originalRecipients: (id) => (id === original ? [A.deviceId] : undefined) })).ok).toBe(true);
    expect(receiveEnvelope(reply, ctx({ originalRecipients: () => [C.deviceId] }))).toEqual({ ok: false, error: 'reply_not_allowed' });
    expect(receiveEnvelope(reply, ctx())).toEqual({ ok: false, error: 'reply_not_allowed' });
  });

  it('rejects replays', () => {
    const env = make(clip);
    expect(receiveEnvelope(env, ctx({ hasSeen: (id) => id === env.msg_id }))).toEqual({ ok: false, error: 'replay' });
  });
});
