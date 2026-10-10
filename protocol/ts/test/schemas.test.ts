import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { Ajv2020 } from 'ajv/dist/2020.js';
import { describe, expect, it } from 'vitest';
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
    ['notification.removed@1', { key: 'k' }, true],
    ['notification.removed@1', {}, false],
    ['notification.dismiss@1', { key: 'k' }, true],
    ['clipboard.set@1', { mime: 'text/plain', text: 'x', sensitive: true, source: 'mac' }, true],
    ['clipboard.set@1', { mime: 'text/plain', text: 'x' }, false],
    ['device.status@1', {}, true],
    ['device.status@1', { battery: 100, charging: false }, true],
    ['device.status@1', { battery: 101 }, false],
    ['device.status@1', { battery: 50.5 }, false],
  ];
  it.each(bodies)('%s body validator matches the schema: %j', (type, body, expected) => {
    expect(validate(type, body)).toBe(expected);
    expect(isValidBody(type, body)).toBe(expected);
  });

  it('record validator matches the schema', () => {
    const record = vectors('record.json').seal.record;
    const variants = [record, { ...record, kind: 'tablet' }, { ...record, receive: { 'clipboard.set@1': 'all' } }, { ...record, sign_pub: 'AA' }];
    for (const v of variants) expect(isValidDeviceRecord(v)).toBe(validate('device-record', v));
  });
});
