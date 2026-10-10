#!/usr/bin/env node
// A simulated Android phone for end-to-end tests of the macOS app (see macos/scripts/e2e.sh).
//
//   node macos/scripts/fake-phone.mjs --invite-file <path> [--clipboard-text <text>]
//
// Waits for the app to write a `carom://pair` URI to --invite-file (Debug launch argument
// -caromInviteFile), joins through it with real crypto from @carom/protocol, publishes its
// record, then sends notification.posted@1, notification.removed@1 for a second key, and
// clipboard.set@1 to the Mac. It also fetches what the Mac sends to it.
// Prints one line per step; never prints keys, tokens or the pair URI.
// Requires `npm install` at the repo root and a built protocol/ts (npm run build there).

import { existsSync, readFileSync } from 'node:fs';
import { parseArgs } from 'node:util';
import {
  b64decode,
  b64encode,
  buildPairAccept,
  collapseRealKey,
  collapseValue,
  createUuidV7Generator,
  DEFAULT_TTL_S,
  defaultReceive,
  encodePayload,
  generateIdentity,
  openRecord,
  openWelcome,
  parseQrUri,
  receiveEnvelope,
  routeRecipients,
  sealEnvelope,
  sealRecord,
} from '@carom/protocol';

const { values: args } = parseArgs({
  options: {
    'invite-file': { type: 'string' },
    'clipboard-text': { type: 'string', default: `carom-e2e-${Date.now()}` },
    'wait-ms': { type: 'string', default: '60000' },
  },
});
if (!args['invite-file']) {
  console.log('usage: node macos/scripts/fake-phone.mjs --invite-file <path> [--clipboard-text <text>]');
  process.exit(2);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const fail = (msg) => {
  console.log(`FAIL  ${msg}`);
  process.exit(1);
};
const ok = (msg) => console.log(`OK    ${msg}`);

let HUB = '';
async function api(method, path, { token, body } = {}) {
  const headers = {};
  if (token) headers.authorization = `Bearer ${token}`;
  if (body !== undefined) headers['content-type'] = 'application/json';
  const res = await fetch(HUB + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

const id = generateIdentity();
const phone = { id, deviceId: id.deviceId, token: null, keys: new Map(), kv: 0, directory: new Map() };
const nextMsgId = createUuidV7Generator();

function record() {
  return {
    id: phone.deviceId,
    name: 'E2E Phone',
    kind: 'phone',
    platform: 'android',
    app_version: 'e2e-0',
    sign_pub: b64encode(id.signPub),
    kx_pub: b64encode(id.kxPub),
    caps: ['clipboard.set@1', 'notification.dismiss@1'],
    receive: defaultReceive('phone'),
    updated: Date.now(),
  };
}

async function refreshDirectory() {
  const r = await api('GET', '/v1/devices', { token: phone.token });
  if (r.status !== 200) fail(`GET /v1/devices -> ${r.status}`);
  phone.directory.clear();
  for (const d of r.body.devices) {
    if (d.record === undefined || d.id === phone.deviceId) continue;
    const opened = openRecord(d.record, { groupKey: phone.keys.get(phone.kv), kv: phone.kv });
    if (opened.ok && opened.record.sign_pub === d.sign_pub) phone.directory.set(d.id, opened.record);
  }
}

async function send(type, body) {
  const to = routeRecipients({ type, senderId: phone.deviceId, directory: [...phone.directory.values()] });
  if (to.length === 0) fail(`${type}: no recipients`);
  const realKey = collapseRealKey(type, body);
  const envelope = sealEnvelope({
    msg_id: nextMsgId(),
    from: phone.deviceId,
    to,
    ...(realKey !== null ? { collapse: collapseValue(phone.keys.get(phone.kv), phone.deviceId, realKey) } : {}),
    ttl_s: DEFAULT_TTL_S[type],
    plaintext: encodePayload({ type, ts: Date.now(), body }),
    groupKey: phone.keys.get(phone.kv),
    kv: phone.kv,
    signSk: id.signSk,
  });
  const r = await api('POST', '/v1/messages', { token: phone.token, body: { envelope } });
  if (r.status !== 200) fail(`POST ${type} -> ${r.status} ${r.body?.error?.code}`);
  ok(`sent ${type} (seq ${r.body.seq})`);
  return envelope.msg_id;
}

// 1. Wait for the invite the app writes.
const deadline = Date.now() + Number(args['wait-ms']);
while (!existsSync(args['invite-file'])) {
  if (Date.now() > deadline) fail('no invite file');
  await sleep(250);
}
await sleep(200);
const qr = parseQrUri(readFileSync(args['invite-file'], 'utf8').trim());
if (qr?.kind !== 'pair') fail('invite file does not hold a pair URI');
HUB = qr.hub.replace(/\/+$/, '');
ok('read pair invite');

// 2. Join (SPEC §8 step 2 and 5).
const accept = await api('POST', '/v1/pair/accept', { body: buildPairAccept(qr.pairId, qr.pairSecret, id) });
if (accept.status !== 200) fail(`POST /v1/pair/accept -> ${accept.status}`);
phone.token = accept.body.token;
ok(`accepted; fingerprint ${phone.deviceId.slice(4, 12)}`);
let welcome = null;
while (welcome === null) {
  if (Date.now() > deadline) fail('welcome never arrived (was the candidate approved?)');
  const r = await api('GET', `/v1/pair/${qr.pairId}/welcome`, { token: phone.token });
  if (r.status === 200) welcome = r.body;
  else await sleep(1000);
}
const opened = openWelcome(welcome.box, qr.pairId, phone.deviceId, id.kxSk, qr.pairSecret);
if (!opened.ok) fail(`openWelcome: ${opened.error}`);
for (const k of opened.welcome.keys) phone.keys.set(k.kv, b64decode(k.k));
phone.kv = Math.max(...phone.keys.keys());
ok(`welcome opened (kv ${phone.kv})`);
const sealed = sealRecord({ record: record(), groupKey: phone.keys.get(phone.kv), kv: phone.kv, signSk: id.signSk });
if ((await api('PUT', '/v1/devices/self/record', { token: phone.token, body: { record: sealed } })).status !== 200) {
  fail('PUT record');
}
await refreshDirectory();
if (!phone.directory.has(opened.welcome.inviter)) fail('the inviter is not a trusted member');
ok(`directory: ${phone.directory.size} member(s), inviter trusted`);

// 3. Give the Mac a moment to see our record (it refreshes on the `dir` frame).
await sleep(2000);

// 4. Notifications and clipboard to the Mac.
await send('notification.posted@1', {
  key: '0|com.example.chat|1|null|42',
  app: { id: 'com.example.chat', name: 'Chat' },
  title: 'Anna',
  text: 'E2E test notification. Your code is 482913.',
  posted_at: Date.now(),
  redacted: false,
});
await send('notification.posted@1', {
  key: '0|com.example.chat|1|null|43',
  app: { id: 'com.example.chat', name: 'Chat' },
  title: 'Temporary',
  text: 'This one is removed again.',
  posted_at: Date.now(),
  redacted: false,
});
await sleep(1000);
await send('notification.removed@1', { key: '0|com.example.chat|1|null|43' });
await send('clipboard.set@1', { mime: 'text/plain', text: args['clipboard-text'], sensitive: false });

// 5. Fetch what the Mac sent to us (types only).
await sleep(1500);
const page = await api('GET', '/v1/messages?limit=50', { token: phone.token });
const keys = new Map(phone.keys);
let last = 0;
for (const m of page.body?.messages ?? []) {
  last = Math.max(last, m.seq);
  const res = receiveEnvelope(m.envelope, {
    selfId: phone.deviceId,
    directory: new Map([...phone.directory].map(([k, r]) => [k, b64decode(r.sign_pub)])),
    keys,
    now: Date.now(),
    hasSeen: () => false,
  });
  ok(`received from the Mac: ${res.ok ? res.payload.type : `dropped ${res.error}`}`);
}
if (last > 0) await api('POST', '/v1/acks', { token: phone.token, body: { up_to: last } });
ok('done');
