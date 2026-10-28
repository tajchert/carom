import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { Ajv2020 } from 'ajv/dist/2020.js';
import { describe, expect, it } from 'vitest';
import { isValidBlobRef, sealBlob } from '../src/blob.ts';
import { isValidBody, MESSAGE_TYPES, type MessageType } from '../src/messages.ts';
import { isValidDeviceRecord } from '../src/record.ts';

const ROOT = join(import.meta.dirname, '..', '..');
const SCHEMAS = join(ROOT, 'schemas');
const vectors = (name: string): any => JSON.parse(readFileSync(join(ROOT, 'vectors', name), 'utf8'));

function schemaFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    return statSync(p).isDirectory() ? schemaFiles(p) : f.endsWith('.schema.json') ? [p] : [];
  });
}

const ajv = new Ajv2020({ strict: true, allErrors: true });
for (const file of schemaFiles(SCHEMAS)) ajv.addSchema(JSON.parse(readFileSync(file, 'utf8')));
const validate = (id: string, value: unknown): boolean => {
  const fn = ajv.getSchema(`urn:carom:v1:${id}`);
  if (!fn) throw new Error(`schema ${id} not found`);
  return fn(value) as boolean;
};
const REF = sealBlob(new Uint8Array(10), 'image/webp', new Uint8Array(32).fill(1), { w: 96, h: 96 }).ref;
const MIN_POSTED = { key: 'k', app: { id: 'a', name: 'A' }, posted_at: 1, redacted: false };

describe('JSON Schemas', () => {
  it('all compile under draft 2020-12 strict mode', () => {
    const ids = ['common', 'envelope', 'payload', 'device-record', 'sealed-record', 'keyring-version', 'welcome', ...MESSAGE_TYPES];
    for (const id of ids) expect(ajv.getSchema(`urn:carom:v1:${id}`), id).toBeDefined();
  });

  it('accept the sealed envelopes and payloads from the vectors', () => {
    for (const c of vectors('envelope_seal.json').cases) {
      expect(validate('envelope', c.envelope), c.name).toBe(true);
      expect(validate('payload', c.payload), c.name).toBe(true);
    }
  });

  it('agree with the Hub shape vectors except for rules only code can check', () => {
    const codeOnly = new Set(['to contains from', 'serialized size 98305']);
    for (const c of vectors('envelope_shape.json').cases) {
      const expected = codeOnly.has(c.name) ? true : c.expected === 'ok';
      expect(validate('envelope', c.envelope), c.name).toBe(expected);
    }
  });

  it('accept records, keyring bodies and welcomes from the vectors', () => {
    const rec = vectors('record.json');
    expect(validate('device-record', rec.seal.record)).toBe(true);
    expect(validate('sealed-record', rec.seal.sealed)).toBe(true);
    expect(validate('sealed-record', { ...rec.seal.sealed, extra: 1 })).toBe(false);
    expect(validate('keyring-version', vectors('rekey.json').keyring_put_body)).toBe(true);
    expect(validate('welcome', vectors('pairing.json').welcome.welcome)).toBe(true);
  });

  it('agree with the message vectors', () => {
    const m = vectors('messages.json');
    for (const c of m.valid) expect(validate('payload', c.payload), c.name).toBe(true);
    for (const c of m.invalid_bodies) expect(validate(c.type, c.body), c.name).toBe(false);
    for (const c of m.missing_reply_to) expect(validate('payload', c.payload), c.name).toBe(false);
  });

  it('b64 patterns reject non-canonical values', () => {
    for (const c of vectors('b64.json').encode) {
      expect(ajv.validate({ $ref: 'urn:carom:v1:common#/$defs/b64' }, c.b64), c.b64).toBe(true);
    }
    for (const c of vectors('b64.json').decode_invalid) {
      expect(ajv.validate({ $ref: 'urn:carom:v1:common#/$defs/b64' }, c.input), c.input).toBe(false);
    }
    for (const c of vectors('device_id.json').invalid_device_ids) {
      expect(ajv.validate({ $ref: 'urn:carom:v1:common#/$defs/device_id' }, c.value), c.value).toBe(false);
    }
  });

  it('payload schema enforces per-type bodies and reply_to for dismiss', () => {
    const ok = { type: 'notification.dismiss@1', ts: 1, reply_to: '01928f3c-7b1e-7c4a-9d2f-5e8a1b2c3d4e', body: { key: 'k' } };
    expect(validate('payload', ok)).toBe(true);
    const { reply_to: _, ...noReply } = ok;
    expect(validate('payload', noReply)).toBe(false);
    expect(validate('payload', { type: 'clipboard.set@1', ts: 1, body: { mime: 'text/html', text: '', sensitive: false } })).toBe(false);
    expect(validate('payload', { type: 'future.type@1', ts: 1, body: { anything: true } })).toBe(true);
    expect(validate('payload', { type: 'device.status@1', ts: -1, body: {} })).toBe(false);
  });

  const bodies: Array<[MessageType, unknown, boolean]> = [
    ['notification.posted@1', { key: 'k', app: { id: 'a', name: 'A' }, posted_at: 1, redacted: false }, true],
    ['notification.posted@1', { key: 'k', app: { id: 'a', name: 'A' }, posted_at: 1, redacted: false, ongoing: true, extra: 1 }, true],
    ['notification.posted@1', { key: 'k', app: { id: 'a' }, posted_at: 1, redacted: false }, false],
    ['notification.posted@1', { key: 'k', app: { id: 'a', name: 'A' }, posted_at: 1 }, false],
    ['notification.posted@1', { key: 'k', app: { id: 'a', name: 'A' }, posted_at: 1, redacted: false, title: 5 }, false],
    ['notification.posted@1', { ...MIN_POSTED, app: { id: 'a', name: 'A', icon: REF }, redacted: true }, true],
    [
      'notification.posted@1',
      {
        ...MIN_POSTED,
        template: 'messaging',
        color: -16_734_076,
        colorized: false,
        large_icon: REF,
        conversation: { is_group: true },
        messages: [{ sender: { name: 'Anna', icon: REF }, text: 'hi', ts: 1, image: REF }],
        actions: [{ index: 0, title: 'Reply', semantic: 1, reply: { choices: ['ok'] } }],
      },
      true,
    ],
    [
      'notification.posted@1',
      {
        ...MIN_POSTED,
        template: 'progress',
        progress: { value: 1, max: 2, indeterminate: false, segments: [{ length: 1, color: -1 }], points: [{ position: 1 }], tracker_icon: REF },
        live: { promoted: true },
        chronometer: { count_down: false },
        when: 5,
        show_when: true,
      },
      true,
    ],
    ['notification.posted@1', { ...MIN_POSTED, call: { type: 'incoming', person: { name: 'Anna' } }, group: { key: 'g', summary: false, alert: 'all' }, channel: { id: 'c', importance: 4 } }, true],
    ['notification.posted@1', { ...MIN_POSTED, color: 4_278_233_220 }, false],
    ['notification.posted@1', { ...MIN_POSTED, lines: Array(8).fill('x') }, false],
    ['notification.posted@1', { ...MIN_POSTED, actions: Array.from({ length: 7 }, (_, i) => ({ index: i, title: 't' })) }, false],
    ['notification.posted@1', { ...MIN_POSTED, large_icon: { ...REF, id: 'x' } }, false],
    ['notification.posted@1', { ...MIN_POSTED, conversation: { title: 't' } }, false],
    ['notification.posted@1', { ...MIN_POSTED, progress: { value: 1, max: 2 } }, false],
    ['notification.removed@1', { key: 'k' }, true],
    ['notification.removed@1', {}, false],
    ['notification.dismiss@1', { key: 'k' }, true],
    ['clipboard.set@1', { mime: 'text/plain', text: 'x', sensitive: true, source: 'mac' }, true],
    ['clipboard.set@1', { mime: 'text/plain', text: 'x' }, false],
    ['device.status@1', {}, true],
    ['device.status@1', { battery: 100, charging: false }, true],
    ['device.status@1', { battery: 101 }, false],
    ['device.status@1', { battery: 50.5 }, false],
    ['notification.action@1', { key: 'k', action: 'answer' }, true],
    ['notification.action@1', { key: 'k', action: { index: 0, title: 'Reply', reply_text: 'ok' } }, true],
    ['notification.action@1', { key: 'k', action: 'press' }, false],
    ['notification.action@1', { key: 'k', action: { index: 0 } }, false],
    ['notification.action_result@1', { ok: true }, true],
    ['notification.action_result@1', { ok: false, error: 'gone' }, true],
    ['notification.action_result@1', { error: 'gone' }, false],
    ['media.state@1', { session: 's', app: { id: 'a', name: 'A' }, state: 'playing', controls: ['play'] }, true],
    ['media.state@1', { session: '', app: { id: 'a', name: 'A' }, state: 'playing', controls: [] }, false],
    ['media.state@1', { session: 's', app: { id: 'a', name: 'A' }, state: 'playing' }, false],
    ['media.command@1', { session: 's', command: 'next' }, true],
    ['media.command@1', { session: 's', command: 'seek', position_ms: 1_000 }, true],
    ['media.command@1', { session: 's', command: 'seek' }, false],
    ['media.command@1', { session: 's', command: 'stop' }, false],
    ['notification.resync@1', {}, true],
    ['notification.posted@1', { ...MIN_POSTED, resync: true }, true],
    ['notification.posted@1', { ...MIN_POSTED, resync: 'yes' }, false],
  ];
  it.each(bodies)('%s body validator matches the schema: %j', (type, body, expected) => {
    expect(validate(type, body)).toBe(expected);
    expect(isValidBody(type, body)).toBe(expected);
  });

  it('payload schema requires reply_to for actions, results and media commands', () => {
    const replyTo = '01928f3c-7b1e-7c4a-9d2f-5e8a1b2c3d4e';
    const cases: Array<[string, Record<string, unknown>]> = [
      ['notification.action@1', { key: 'k', action: 'answer' }],
      ['notification.action_result@1', { ok: true }],
      ['media.command@1', { session: 's', command: 'play' }],
    ];
    for (const [type, body] of cases) {
      expect(validate('payload', { type, ts: 1, reply_to: replyTo, body }), type).toBe(true);
      expect(validate('payload', { type, ts: 1, body }), type).toBe(false);
    }
    const state = { session: 's', app: { id: 'a', name: 'A' }, state: 'paused', controls: [] };
    expect(validate('payload', { type: 'media.state@1', ts: 1, body: state })).toBe(true);
  });

  it('blob_ref schema matches isValidBlobRef', () => {
    const variants: unknown[] = [
      REF,
      { ...REF, extra: 1 },
      { ...REF, id: REF.id.slice(1) },
      { ...REF, key: REF.key + 'A' },
      { ...REF, mime: '' },
      { ...REF, size: -1 },
      { ...REF, size: 1_044_477 },
      { ...REF, hash: REF.id },
      { ...REF, w: 0 },
      { id: REF.id },
    ];
    for (const v of variants) {
      expect(ajv.validate({ $ref: 'urn:carom:v1:common#/$defs/blob_ref' }, v), JSON.stringify(v)).toBe(isValidBlobRef(v));
    }
  });

  it('record validator matches the schema', () => {
    const record = vectors('record.json').seal.record;
    const variants = [record, { ...record, kind: 'tablet' }, { ...record, receive: { 'clipboard.set@1': 'all' } }, { ...record, sign_pub: 'AA' }];
    for (const v of variants) expect(isValidDeviceRecord(v)).toBe(validate('device-record', v));
  });
});
