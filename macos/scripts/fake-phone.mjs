#!/usr/bin/env node
// A simulated Android phone for end-to-end tests of the macOS app (see macos/scripts/e2e.sh).
//
//   node macos/scripts/fake-phone.mjs --invite-file <path> [--clipboard-text <text>]
//                                     [--expect-action] [--expect-media]
//
// Waits for the app to write a `carom://pair` URI to --invite-file (Debug launch argument
// -caromInviteFile), joins through it with real crypto from @carom/protocol, publishes its
// record, then sends notification.posted@1, notification.removed@1 for a second key, and
// clipboard.set@1 to the Mac. Then the V2 part: it uploads an avatar as an encrypted blob
// (convergent key, PUT /v1/blobs/:id) and checks it reads back, posts a rich messaging
// notification with that avatar and a reply action, a progress Live Update and two updates of
// it, and a media.state@1. Finally it polls what the Mac sends: it answers every
// notification.action@1 with notification.action_result@1 (ok), and notes media.command@1.
// --expect-action / --expect-media make a missing action or command a failure (the app sends
// them with the Debug launch arguments -caromAutoReply and -caromAutoMedia).
// Prints one line per step; never prints keys, tokens or the pair URI.
// Requires `npm install` at the repo root and a built protocol/ts (npm run build there).

import { existsSync, readFileSync } from 'node:fs';
import { parseArgs } from 'node:util';
import { crc32, deflateSync } from 'node:zlib';
import {
  b64decode,
  b64encode,
  buildPairAccept,
  collapseRealKey,
  collapseValue,
  convergentKey,
  createUuidV7Generator,
  DEFAULT_TTL_S,
  defaultReceive,
  encodePayload,
  generateIdentity,
  openBlob,
  openRecord,
  openWelcome,
  parseQrUri,
  receiveEnvelope,
  routeRecipients,
  sealBlob,
  sealEnvelope,
  sealRecord,
} from '@carom/protocol';

const { values: args } = parseArgs({
  options: {
    'invite-file': { type: 'string' },
    'clipboard-text': { type: 'string', default: `carom-e2e-${Date.now()}` },
    'wait-ms': { type: 'string', default: '60000' },
    'action-wait-ms': { type: 'string', default: '45000' },
    'expect-action': { type: 'boolean', default: false },
    'expect-media': { type: 'boolean', default: false },
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

/** Raw bytes in and out, for blobs. */
async function rawApi(method, path, { token, bytes } = {}) {
  const headers = { authorization: `Bearer ${token}` };
  if (bytes !== undefined) headers['content-type'] = 'application/octet-stream';
  const res = await fetch(HUB + path, { method, headers, body: bytes });
  return { status: res.status, bytes: new Uint8Array(await res.arrayBuffer()) };
}

/** A small RGBA PNG: a filled circle with a lighter ring, so the avatar is easy to spot. */
function avatarPng(size = 96) {
  const chunk = (type, data) => {
    const out = Buffer.alloc(12 + data.length);
    out.writeUInt32BE(data.length, 0);
    out.write(type, 4, 'ascii');
    data.copy(out, 8);
    out.writeUInt32BE(crc32(Buffer.concat([Buffer.from(type, 'ascii'), data])), 8 + data.length);
    return out;
  };
  const rows = Buffer.alloc(size * (1 + size * 4));
  const c = (size - 1) / 2;
  for (let y = 0; y < size; y++) {
    rows[y * (1 + size * 4)] = 0;
    for (let x = 0; x < size; x++) {
      const d = Math.hypot(x - c, y - c);
      const px = y * (1 + size * 4) + 1 + x * 4;
      if (d > size / 2) continue;
      const ring = d > size / 2 - 8;
      rows.set(ring ? [255, 214, 102, 255] : [233, 84, 32, 255], px);
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr.set([8, 6, 0, 0, 0], 8);
  return new Uint8Array(
    Buffer.concat([
      Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
      chunk('IHDR', ihdr),
      chunk('IDAT', deflateSync(rows)),
      chunk('IEND', Buffer.alloc(0)),
    ]),
  );
}

const id = generateIdentity();
const phone = { id, deviceId: id.deviceId, token: null, keys: new Map(), kv: 0, directory: new Map() };
const nextMsgId = createUuidV7Generator();
/** `to` of every message we sent, by msg_id: replies are only accepted from those (SPEC §3.3). */
const sentTo = new Map();

function record() {
  return {
    id: phone.deviceId,
    name: 'E2E Phone',
    kind: 'phone',
    platform: 'android',
    app_version: 'e2e-0',
    sign_pub: b64encode(id.signPub),
    kx_pub: b64encode(id.kxPub),
    caps: ['clipboard.set@1', 'notification.dismiss@1', 'notification.action@1', 'media.command@1'],
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

async function send(type, body, { replyTo, replyToSender } = {}) {
  const to = routeRecipients({ type, senderId: phone.deviceId, directory: [...phone.directory.values()], replyToSender });
  if (to.length === 0) fail(`${type}: no recipients`);
  const realKey = collapseRealKey(type, body);
  const envelope = sealEnvelope({
    msg_id: nextMsgId(),
    from: phone.deviceId,
    to,
    ...(realKey !== null ? { collapse: collapseValue(phone.keys.get(phone.kv), phone.deviceId, realKey) } : {}),
    ttl_s: DEFAULT_TTL_S[type],
    plaintext: encodePayload({ type, ts: Date.now(), ...(replyTo !== undefined ? { reply_to: replyTo } : {}), body }),
    groupKey: phone.keys.get(phone.kv),
    kv: phone.kv,
    signSk: id.signSk,
  });
  const r = await api('POST', '/v1/messages', { token: phone.token, body: { envelope } });
  if (r.status !== 200) fail(`POST ${type} -> ${r.status} ${r.body?.error?.code}`);
  ok(`sent ${type} (seq ${r.body.seq})`);
  sentTo.set(envelope.msg_id, to);
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

// 5. V2: an avatar as an encrypted blob (SPEC §11). A convergent key, as for icons, so the blob
// is not tied to one msg_id and survives the conversation's updates.
const avatar = avatarPng();
const sealedAvatar = sealBlob(avatar, 'image/png', convergentKey(phone.keys.get(phone.kv), avatar), { w: 96, h: 96 });
const put = await rawApi('PUT', `/v1/blobs/${sealedAvatar.ref.id}?ttl_s=86400`, {
  token: phone.token,
  bytes: sealedAvatar.ct,
});
if (put.status !== 201 && put.status !== 200) fail(`PUT /v1/blobs -> ${put.status}`);
ok(`uploaded avatar blob (${sealedAvatar.ct.length} bytes of ciphertext)`);
const got = await rawApi('GET', `/v1/blobs/${sealedAvatar.ref.id}`, { token: phone.token });
if (got.status !== 200) fail(`GET /v1/blobs -> ${got.status}`);
const reopened = openBlob(sealedAvatar.ref, got.bytes);
if (reopened === null || Buffer.compare(Buffer.from(reopened), Buffer.from(avatar)) !== 0) fail('blob GET does not open to the avatar');
ok('blob GET round trip: ciphertext verified and decrypted');

// 6. A rich messaging notification with the avatar, a reply action and a plain button.
const anna = { name: 'Anna Example', key: 'anna', icon: sealedAvatar.ref };
const now = Date.now();
const chatKey = '0|com.example.chat|7|null|100';
const chatMsgId = await send('notification.posted@1', {
  key: chatKey,
  app: { id: 'com.example.chat', name: 'Chat' },
  title: 'Anna Example',
  text: 'Are we still on for lunch?',
  category: 'msg',
  posted_at: now,
  redacted: false,
  template: 'messaging',
  large_icon: sealedAvatar.ref,
  conversation: { is_group: false },
  messages: [
    { sender: anna, text: 'Hi! I booked a table at noon.', ts: now - 60_000 },
    { sender: anna, text: 'Are we still on for lunch?', ts: now },
  ],
  actions: [
    { index: 0, title: 'Reply', semantic: 1, reply: { label: 'Reply to Anna', choices: ['Yes!', 'Running late'] } },
    { index: 1, title: 'Mark as read', semantic: 2 },
  ],
  can_open: true,
});

// 7. A progress Live Update, then two updates of the same key (they collapse at the Hub).
const rideKey = '0|com.example.rides|9|null|200';
const ride = (value, text) => ({
  key: rideKey,
  app: { id: 'com.example.rides', name: 'Rides' },
  title: 'Your ride is on its way',
  text,
  category: 'progress',
  posted_at: now,
  redacted: false,
  ongoing: true,
  template: 'progress',
  progress: { value, max: 100, indeterminate: false, segments: [{ length: 60 }, { length: 40 }], points: [{ position: 60 }] },
  live: { promoted: true, short_critical_text: `${Math.round((100 - value) / 10)} min` },
  when: now + (100 - value) * 6_000,
  show_when: true,
  chronometer: { count_down: true },
});
await send('notification.posted@1', ride(20, 'Driver is 8 minutes away'));
await sleep(1500);
await send('notification.posted@1', ride(50, 'Driver is 5 minutes away'));
await sleep(1500);
await send('notification.posted@1', ride(80, 'Driver is 2 minutes away'));

// 8. Now Playing.
await send('media.state@1', {
  session: 'e2e-session-1',
  app: { id: 'com.example.music', name: 'Music' },
  title: 'E2E Song',
  artist: 'The Testers',
  album: 'Round Trip',
  art: sealedAvatar.ref,
  state: 'playing',
  position_ms: 30_000,
  position_at: Date.now(),
  duration_ms: 200_000,
  controls: ['play', 'pause', 'next', 'previous', 'seek'],
});

// 9. Poll what the Mac sends (types only); answer actions with an ok result.
const keys = new Map(phone.keys);
const seen = new Set();
let actionAnswered = false;
let mediaCommand = false;
const actionDeadline = Date.now() + Number(args['action-wait-ms']);
const wantAction = args['expect-action'];
const wantMedia = args['expect-media'];
while (Date.now() < actionDeadline && !((!wantAction || actionAnswered) && (!wantMedia || mediaCommand))) {
  const page = await api('GET', '/v1/messages?limit=50', { token: phone.token });
  let last = 0;
  for (const m of page.body?.messages ?? []) {
    last = Math.max(last, m.seq);
    const res = receiveEnvelope(m.envelope, {
      selfId: phone.deviceId,
      directory: new Map([...phone.directory].map(([k, r]) => [k, b64decode(r.sign_pub)])),
      keys,
      now: Date.now(),
      hasSeen: (id) => seen.has(id),
      originalRecipients: (id) => sentTo.get(id),
    });
    if (!res.ok) {
      if (res.error !== 'replay') ok(`received from the Mac: dropped ${res.error}`);
      continue;
    }
    seen.add(m.envelope.msg_id);
    const { type, body, reply_to: replyTo } = res.payload;
    ok(`received from the Mac: ${type}`);
    if (type === 'notification.action@1') {
      const isReply = typeof body.action === 'object' && typeof body.action.reply_text === 'string';
      if (body.key !== chatKey || replyTo !== chatMsgId) fail('notification.action@1 does not point at the chat card');
      ok(`action is ${isReply ? 'an inline reply' : 'not a reply'} for the chat card`);
      await send('notification.action_result@1', { ok: true }, { replyTo: m.envelope.msg_id, replyToSender: m.envelope.from });
      actionAnswered = true;
    } else if (type === 'media.command@1') {
      ok(`media command ${body.command} for ${body.session === 'e2e-session-1' ? 'our session' : 'another session'}`);
      mediaCommand = true;
    }
  }
  if (last > 0) await api('POST', '/v1/acks', { token: phone.token, body: { up_to: last } });
  await sleep(1000);
}
if (wantAction && !actionAnswered) fail('no notification.action@1 from the Mac');
if (wantMedia && !mediaCommand) fail('no media.command@1 from the Mac');
ok('done');
