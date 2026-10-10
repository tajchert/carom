import { describe, expect, it } from 'vitest';
import { b64encode } from '../src/b64.ts';
import { generateIdentity } from '../src/device.ts';
import { tokenKey } from '../src/kdf.ts';
import { collapseRealKey, DEFAULT_TTL_S, isMessageType, MESSAGE_TYPES } from '../src/messages.ts';
import { buildPairUri, buildSetupUri, isValidHubUrl, parseQrUri, percentEncode } from '../src/qr.ts';
import type { DeviceRecord } from '../src/record.ts';
import { defaultReceive, routeRecipients } from '../src/routing.ts';
import { makeToken, parseToken, tokenHash, verifyToken } from '../src/token.ts';
import { compareMsgIds, createUuidV7Generator, formatUuidV7, isUuidV7, uuidV7Timestamp } from '../src/uuidv7.ts';

const fill = (b: number) => (n: number) => new Uint8Array(n).fill(b);
const A = generateIdentity(fill(1));

describe('tokens', () => {
  const key = tokenKey('s'.repeat(32));
  it('make, parse and verify', () => {
    const token = makeToken(key, A.deviceId, { rng: fill(7) });
    expect(token.startsWith(`ct1.${A.deviceId}.`)).toBe(true);
    expect(parseToken(token)).toMatchObject({ deviceId: A.deviceId, rand: new Uint8Array(32).fill(7) });
    expect(verifyToken(token, key)).toBe(A.deviceId);
    expect(verifyToken(token, tokenKey('t'.repeat(32)))).toBeNull();
    expect(verifyToken(42, key)).toBeNull();
    expect(tokenHash(token)).toHaveLength(32);
    expect(makeToken(key, A.deviceId)).not.toBe(makeToken(key, A.deviceId));
  });

  it('rejects bad inputs when making', () => {
    expect(() => makeToken(key, 'dev_x')).toThrow(RangeError);
    expect(() => makeToken(key, A.deviceId, { rand: new Uint8Array(31) })).toThrow(RangeError);
  });
});

describe('QR URIs', () => {
  const hub = 'https://hub.example.com';
  const secret = new Uint8Array(32).fill(3);

  it('percent-encodes everything outside the unreserved set', () => {
    expect(percentEncode('aZ09-._~ :/?#[]@!$&\'()*+,;=%é')).toBe('aZ09-._~%20%3A%2F%3F%23%5B%5D%40%21%24%26%27%28%29%2A%2B%2C%3B%3D%25%C3%A9');
  });

  it('round-trips setup and pair payloads', () => {
    const token = 'tok+en/with=odd&chars ' + 'x'.repeat(32);
    expect(parseQrUri(buildSetupUri(hub, token))).toEqual({ kind: 'setup', hub, setupToken: token });
    expect(parseQrUri(buildPairUri(hub, 'abc_-1abc_-1abc_-1abcd', secret))).toEqual({ kind: 'pair', hub, pairId: 'abc_-1abc_-1abc_-1abcd', pairSecret: secret });
  });

  it('accepts a raw "=" in a value but rejects a raw "+"', () => {
    const t = 'abcdefghijklmnopqrstuvwxyz01234=6789';
    expect(parseQrUri(`carom://setup?v=1&h=https%3A%2F%2Fhub.example.com&t=${t}`)).toEqual({ kind: 'setup', hub: 'https://hub.example.com', setupToken: t });
    expect(parseQrUri(`carom://setup?v=1&h=https%3A%2F%2Fhub.example.com&t=${t.replace('=', '+')}`)).toBeNull();
  });

  it('builders validate their inputs', () => {
    expect(() => buildSetupUri('http://hub.example.com', 'x'.repeat(32))).toThrow(RangeError);
    expect(() => buildSetupUri(hub, 'short')).toThrow(RangeError);
    expect(() => buildPairUri(hub, 'bad id', secret)).toThrow(RangeError);
    expect(() => buildPairUri(hub, 'abc', secret)).toThrow(RangeError);
    expect(() => buildPairUri(hub, 'A'.repeat(23), secret)).toThrow(RangeError);
    expect(() => buildPairUri(hub, 'A'.repeat(21), secret)).toThrow(RangeError);
    expect(() => buildPairUri(hub, 'A'.repeat(22), new Uint8Array(16))).toThrow(RangeError);
    expect(() => buildPairUri('ftp://x', 'ok', secret)).toThrow(RangeError);
  });

  it.each([
    ['https://hub.example.com', true],
    ['https://hub.example.com/base/', true],
    ['http://localhost:8787', true],
    ['http://127.0.0.1:8787', true],
    ['http://[::1]:8787', true],
    ['http://example.com', false],
    ['https://u:p@hub.example.com', false],
    ['https://hub.example.com/?x', false],
    ['https://hub.example.com/#x', false],
    ['https://hub example.com', false],
    ['carom://pair', false],
    ['', false],
  ])('isValidHubUrl(%j) = %s', (url, ok) => {
    expect(isValidHubUrl(url)).toBe(ok);
  });
});

describe('UUIDv7', () => {
  it('is strictly monotonic across same-ms bursts, counter overflow and clock regressions', () => {
    let t = 1_000;
    const clock = [...Array(5000).fill(1_000), 900, 900, 2_000];
    const gen = createUuidV7Generator({ now: () => clock[Math.min(t++ - 1_000, clock.length - 1)]! });
    const ids = clock.map(() => gen());
    for (let i = 1; i < ids.length; i++) expect(compareMsgIds(ids[i - 1]!, ids[i]!)).toBe(-1);
    for (const id of ids) expect(isUuidV7(id)).toBe(true);
    expect(uuidV7Timestamp(ids[0]!)).toBe(1_000);
    expect(uuidV7Timestamp(ids[4999]!)).toBeGreaterThan(1_000);
    expect(uuidV7Timestamp(ids.at(-1)!)).toBe(2_000);
  });

  it('defaults to the system clock', () => {
    const before = Date.now();
    expect(uuidV7Timestamp(createUuidV7Generator()())).toBeGreaterThanOrEqual(before);
  });

  it('formatUuidV7 and helpers validate input', () => {
    expect(() => formatUuidV7(-1, 0, new Uint8Array(8))).toThrow(RangeError);
    expect(() => formatUuidV7(2 ** 48, 0, new Uint8Array(8))).toThrow(RangeError);
    expect(() => formatUuidV7(0, 0x1000, new Uint8Array(8))).toThrow(RangeError);
    expect(() => formatUuidV7(0, 0, new Uint8Array(7))).toThrow(RangeError);
    expect(() => uuidV7Timestamp('nope')).toThrow();
    expect(compareMsgIds('a', 'a')).toBe(0);
    expect(compareMsgIds('b', 'a')).toBe(1);
  });
});

describe('messages and routing', () => {
  it('knows the v1 types, TTLs and collapse keys', () => {
    expect(MESSAGE_TYPES.every(isMessageType)).toBe(true);
    expect(isMessageType('blob.ref@1')).toBe(false);
    expect(DEFAULT_TTL_S['notification.posted@1']).toBe(172_800);
    expect(DEFAULT_TTL_S['clipboard.set@1']).toBe(86_400);
    expect(collapseRealKey('notification.posted@1', { key: 'k', app: { id: 'a', name: 'A' }, posted_at: 0, redacted: false })).toBe('n:k');
    expect(collapseRealKey('notification.removed@1', { key: 'k' })).toBe('n:k');
    expect(collapseRealKey('clipboard.set@1', { mime: 'text/plain', text: '', sensitive: false })).toBe('clip');
    expect(collapseRealKey('device.status@1', {})).toBe('status');
    expect(collapseRealKey('notification.dismiss@1', { key: 'k' })).toBeNull();
  });

  it('default receive rules follow SPEC §5.1', () => {
    expect(defaultReceive('phone')).toEqual({ 'clipboard.set@1': '*', 'notification.dismiss@1': '*' });
    expect(Object.keys(defaultReceive('desktop')).sort()).toEqual(['clipboard.set@1', 'device.status@1', 'notification.posted@1', 'notification.removed@1']);
    expect(defaultReceive('cli')).toEqual({ 'clipboard.set@1': '*' });
  });

  const ids = [1, 2, 3, 4, 5].map((i) => generateIdentity(fill(10 + i)).deviceId) as [string, string, string, string, string];
  const rec = (id: string, receive: DeviceRecord['receive'], caps: string[]): DeviceRecord => ({
    id,
    name: id,
    kind: 'desktop',
    platform: 'macos',
    app_version: '1',
    sign_pub: b64encode(new Uint8Array(32)),
    kx_pub: b64encode(new Uint8Array(32)),
    caps,
    receive,
    updated: 0,
  });
  const T = 'clipboard.set@1';
  const directory = [
    rec(ids[0], { [T]: '*' }, [T]),
    rec(ids[1], { [T]: '*' }, [T]),
    rec(ids[2], { [T]: [ids[0]] }, [T]),
    rec(ids[3], { [T]: [ids[1]] }, [T]),
    rec(ids[4], { [T]: '*' }, []),
  ];

  it('routes by receive rule, caps and revocation, never to the sender', () => {
    const expected = [ids[1], ids[2]].sort();
    expect(routeRecipients({ type: T, senderId: ids[0], directory })).toEqual(expected);
    expect(routeRecipients({ type: T, senderId: ids[0], directory, revoked: new Set([ids[2]]) })).toEqual([ids[1]]);
    expect(routeRecipients({ type: 'device.status@1', senderId: ids[0], directory })).toEqual([]);
    expect(routeRecipients({ type: 'toString', senderId: ids[0], directory: [rec(ids[1], {}, ['toString'])] })).toEqual([]);
  });

  it('routes replies only to the original sender', () => {
    expect(routeRecipients({ type: 'notification.dismiss@1', senderId: ids[0], directory, replyToSender: ids[3] })).toEqual([ids[3]]);
    expect(routeRecipients({ type: 'notification.dismiss@1', senderId: ids[0], directory, replyToSender: ids[0] })).toEqual([]);
  });
});
