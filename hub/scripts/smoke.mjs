#!/usr/bin/env node
// End-to-end smoke test of a deployed Carom Hub (protocol/SPEC.md).
//
//   node hub/scripts/smoke.mjs --url <hub> [--setup-token-file <path>] [--reset]
//
// Drives two simulated devices (a Mac "desktop" and an Android "phone") with real crypto from
// @carom/protocol: setup, pairing, live and backlog delivery, collapse, idempotency, fetch/ack,
// push subscription, revocation, rekey and negative checks. It ends with POST /v1/reset, so the
// Hub is left unclaimed. Prints one PASS/FAIL line per step and exits non-zero on any failure.
//
// The setup token is read from --setup-token-file (JSON with a `setupToken` field, or a raw
// token), default ~/.config/carom-hub/<first label of the hub hostname>.json, or from the
// CAROM_SETUP_TOKEN environment variable. Tokens are never printed.

import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { parseArgs } from 'node:util';
import { webcrypto } from 'node:crypto';
import WebSocket from 'ws';
import {
  b64decode,
  b64encode,
  buildPairAccept,
  buildPairUri,
  buildRekey,
  collapseRealKey,
  collapseValue,
  createUuidV7Generator,
  DEFAULT_TTL_S,
  defaultReceive,
  defaultRng,
  encodePayload,
  equalBytes,
  generateIdentity,
  openRecord,
  openRekey,
  openWelcome,
  parseQrUri,
  receiveEnvelope,
  routeRecipients,
  sealEnvelope,
  sealRecord,
  sealWelcome,
  verifyPairCandidate,
} from '@carom/protocol';

// ---------------------------------------------------------------- arguments

const { values: args } = parseArgs({
  options: {
    url: { type: 'string' },
    'setup-token-file': { type: 'string' },
    reset: { type: 'boolean', default: false },
    help: { type: 'boolean', short: 'h', default: false },
  },
});
if (args.help || !args.url) {
  console.log('usage: node hub/scripts/smoke.mjs --url <hub> [--setup-token-file <path>] [--reset]');
  process.exit(args.help ? 0 : 2);
}
const HUB = args.url.replace(/\/+$/, '');
const WS_URL = HUB.replace(/^http/, 'ws') + '/v1/ws';

function loadSetupToken() {
  if (process.env.CAROM_SETUP_TOKEN) return process.env.CAROM_SETUP_TOKEN;
  const file =
    args['setup-token-file'] ?? join(homedir(), '.config', 'carom-hub', `${new URL(HUB).hostname.split('.')[0]}.json`);
  const text = readFileSync(file, 'utf8').trim();
  try {
    const json = JSON.parse(text);
    if (typeof json?.setupToken === 'string') return json.setupToken;
  } catch {
    // Not JSON: a raw token.
  }
  return text;
}
const SETUP_TOKEN = loadSetupToken();

// ---------------------------------------------------------------- reporting

const results = [];
const notes = [];

class StepError extends Error {}
function check(cond, message) {
  if (!cond) throw new StepError(message);
}

async function step(name, fn) {
  const t0 = performance.now();
  try {
    const detail = await fn();
    const ms = performance.now() - t0;
    results.push({ name, ok: true });
    console.log(`PASS  ${name}  (${ms.toFixed(0)} ms)${detail ? `  ${detail}` : ''}`);
    return true;
  } catch (err) {
    const ms = performance.now() - t0;
    results.push({ name, ok: false });
    console.log(`FAIL  ${name}  (${ms.toFixed(0)} ms)  ${err instanceof Error ? err.message : String(err)}`);
    if (!(err instanceof StepError) && err instanceof Error && err.stack) console.log(redact(err.stack));
    return false;
  }
}

/** Removes anything that looks like a token from text that may be printed. */
function redact(text) {
  let out = String(text).replace(/ct1\.[A-Za-z0-9_.-]+/g, 'ct1.<redacted>');
  if (SETUP_TOKEN) out = out.split(SETUP_TOKEN).join('<setup-token>');
  return out.replace(/("token"\s*:\s*")[^"]*"/g, '$1<redacted>"');
}

// ---------------------------------------------------------------- HTTP

/** Calls the Hub; returns {status, body, ms}. Never throws on HTTP errors. */
async function api(method, path, { token, body, headers = {} } = {}) {
  const h = { ...headers };
  if (token) h.authorization = `Bearer ${token}`;
  if (body !== undefined) h['content-type'] = 'application/json';
  const t0 = performance.now();
  const res = await fetch(HUB + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  const ms = performance.now() - t0;
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = { raw: text.slice(0, 200) };
  }
  return { status: res.status, body: json, ms };
}

function describe(r) {
  return `HTTP ${r.status} ${redact(JSON.stringify(r.body))}`;
}

function expectStatus(r, status, what) {
  check(r.status === status, `${what}: expected ${status}, got ${describe(r)}`);
  return r.body;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ---------------------------------------------------------------- WebSocket

/** A WebSocket with a frame queue; `next(pred)` waits for a matching frame. */
class Socket {
  constructor(token, label) {
    this.label = label;
    this.frames = [];
    this.waiters = [];
    this.closed = null;
    this.ws = new WebSocket(WS_URL, { headers: { authorization: `Bearer ${token}` } });
    this.ws.on('message', (data) => {
      const text = data.toString();
      let frame;
      try {
        frame = JSON.parse(text);
      } catch {
        frame = { raw: text };
      }
      frame.__at = performance.now();
      this.frames.push(frame);
      this.flush();
    });
    this.ws.on('close', (code, reason) => {
      this.closed = { code, reason: reason.toString() };
      this.flush();
    });
    this.ws.on('error', () => {});
    this.opened = new Promise((resolve, reject) => {
      this.ws.once('open', resolve);
      this.ws.once('unexpected-response', (_req, res) => reject(new StepError(`${label} WS upgrade got HTTP ${res.statusCode}`)));
      this.ws.once('error', (e) => reject(new StepError(`${label} WS error: ${redact(e.message)}`)));
    });
  }

  flush() {
    for (const w of [...this.waiters]) {
      const i = this.frames.findIndex(w.pred);
      if (i >= 0) {
        const [frame] = this.frames.splice(i, 1);
        this.waiters.splice(this.waiters.indexOf(w), 1);
        clearTimeout(w.timer);
        w.resolve(frame);
      } else if (this.closed && w.failOnClose) {
        this.waiters.splice(this.waiters.indexOf(w), 1);
        clearTimeout(w.timer);
        w.reject(new StepError(`${this.label} WS closed (${this.closed.code}) while waiting for ${w.what}`));
      }
    }
  }

  next(pred, what, timeoutMs = 10_000, failOnClose = true) {
    return new Promise((resolve, reject) => {
      const w = {
        pred,
        what,
        failOnClose,
        resolve,
        reject,
        timer: setTimeout(() => {
          this.waiters.splice(this.waiters.indexOf(w), 1);
          reject(new StepError(`${this.label}: timed out after ${timeoutMs} ms waiting for ${what}`));
        }, timeoutMs),
      };
      this.waiters.push(w);
      this.flush();
    });
  }

  drain() {
    const out = this.frames;
    this.frames = [];
    return out;
  }

  send(obj) {
    this.ws.send(JSON.stringify(obj));
  }

  /** Closes and waits for the closing handshake; returns its duration in ms, or null on timeout. */
  async close(timeoutMs = 5000) {
    if (this.closed) return 0;
    const t0 = performance.now();
    const done = new Promise((resolve) => this.ws.once('close', () => resolve(performance.now() - t0)));
    this.ws.close(1000, 'bye');
    const ms = await Promise.race([done, sleep(timeoutMs).then(() => null)]);
    if (ms === null) this.ws.terminate();
    return ms;
  }
}

// ---------------------------------------------------------------- devices

function makeDevice(name, kind, platform) {
  const id = generateIdentity();
  return {
    name,
    kind,
    platform,
    id,
    deviceId: id.deviceId,
    token: null,
    keys: new Map(), // kv -> K
    kv: 0,
    seen: new Set(),
    directory: new Map(), // id -> DeviceRecord (trusted members)
    nextMsgId: createUuidV7Generator(),
  };
}

function caps(kind) {
  return kind === 'phone'
    ? ['clipboard.set@1', 'notification.dismiss@1']
    : ['notification.posted@1', 'notification.removed@1', 'device.status@1', 'clipboard.set@1'];
}

function buildRecord(dev) {
  return {
    id: dev.deviceId,
    name: dev.name,
    kind: dev.kind,
    platform: dev.platform,
    app_version: 'smoke-0',
    sign_pub: b64encode(dev.id.signPub),
    kx_pub: b64encode(dev.id.kxPub),
    caps: caps(dev.kind),
    receive: defaultReceive(dev.kind),
    updated: Date.now(),
  };
}

async function publishRecord(dev) {
  const record = sealRecord({ record: buildRecord(dev), groupKey: dev.keys.get(dev.kv), kv: dev.kv, signSk: dev.id.signSk });
  const r = await api('PUT', '/v1/devices/self/record', { token: dev.token, body: { record } });
  expectStatus(r, 200, `${dev.name} PUT record`);
  return r.ms;
}

/** GET /v1/devices and keep members per SPEC §5.3. Returns the raw list. */
async function refreshDirectory(dev) {
  const r = await api('GET', '/v1/devices', { token: dev.token });
  const body = expectStatus(r, 200, `${dev.name} GET /v1/devices`);
  dev.directory.clear();
  for (const d of body.devices) {
    if (d.record === undefined) continue;
    const opened = openRecord(d.record, { groupKey: dev.keys.get(dev.kv), kv: dev.kv });
    if (!opened.ok) continue;
    // The Hub's view of the key must agree with the signed record.
    if (opened.record.sign_pub !== d.sign_pub || opened.record.kx_pub !== d.kx_pub) continue;
    dev.directory.set(d.id, opened.record);
  }
  return body.devices;
}

function trustedKeys(dev) {
  return new Map([...dev.directory].map(([id, rec]) => [id, b64decode(rec.sign_pub)]));
}

function seal(dev, type, body, { msgId, to, collapse = true } = {}) {
  const realKey = collapse ? collapseRealKey(type, body) : null;
  const recipients = to ?? routeRecipients({ type, senderId: dev.deviceId, directory: [...dev.directory.values()] });
  return sealEnvelope({
    msg_id: msgId ?? dev.nextMsgId(),
    from: dev.deviceId,
    to: recipients,
    ...(realKey !== null ? { collapse: collapseValue(dev.keys.get(dev.kv), dev.deviceId, realKey) } : {}),
    ttl_s: DEFAULT_TTL_S[type],
    plaintext: encodePayload({ type, ts: Date.now(), body }),
    groupKey: dev.keys.get(dev.kv),
    kv: dev.kv,
    signSk: dev.id.signSk,
  });
}

function receive(dev, envelope) {
  const res = receiveEnvelope(envelope, {
    selfId: dev.deviceId,
    directory: trustedKeys(dev),
    keys: dev.keys,
    now: Date.now(),
    hasSeen: (id) => dev.seen.has(id),
  });
  if (res.ok) dev.seen.add(res.envelope.msg_id);
  return res;
}

function postEnvelope(dev, envelope) {
  return api('POST', '/v1/messages', { token: dev.token, body: { envelope } });
}

function notificationBody(key, title) {
  return { key, app: { id: 'com.example.chat', name: 'Chat' }, title, text: 'smoke test', posted_at: Date.now(), redacted: false };
}

// ---------------------------------------------------------------- run

const mac = makeDevice('Smoke Mac', 'desktop', 'macos');
const phone = makeDevice('Smoke Phone', 'phone', 'android');
let macWs = null;
let phoneWs = null;
const latency = {};
let liveEnvelope = null;
let staleMsgId = null;

async function run() {
  // 1
  if (
    !(await step('1. GET /v1/info', async () => {
      let r = await api('GET', '/v1/info');
      let info = expectStatus(r, 200, 'GET /v1/info');
      check(info.name === 'carom-hub' && info.protocol === 1, `unexpected info ${JSON.stringify(info)}`);
      let note = '';
      if (info.setup_done) {
        check(args.reset, 'hub is already set up (setup_done=true); rerun with --reset to wipe it');
        const rr = await api('POST', '/v1/reset', { body: { setup_token: SETUP_TOKEN, confirm: 'erase everything' } });
        expectStatus(rr, 200, 'POST /v1/reset');
        r = await api('GET', '/v1/info');
        info = expectStatus(r, 200, 'GET /v1/info');
        note = 'reset first; ';
      }
      check(info.setup_done === false, 'setup_done is still true');
      check(typeof info.vapid_public_key === 'string' && b64decode(info.vapid_public_key).length === 65, 'bad vapid_public_key');
      return `${note}version=${info.version} setup_done=false`;
    }))
  )
    return;

  // 2
  if (
    !(await step('2. Mac setup + group key kv=1 + record', async () => {
      const r = await api('POST', '/v1/setup', {
        body: { setup_token: SETUP_TOKEN, sign_pub: b64encode(mac.id.signPub), kx_pub: b64encode(mac.id.kxPub) },
      });
      const body = expectStatus(r, 200, 'POST /v1/setup');
      check(body.device_id === mac.deviceId, 'setup device_id does not match device_id(sign_pub)');
      check(typeof body.token === 'string' && body.token.startsWith(`ct1.${mac.deviceId}.`), 'malformed token');
      mac.token = body.token;
      mac.kv = 1;
      mac.keys.set(1, defaultRng(32));
      await publishRecord(mac);
      const info = expectStatus(await api('GET', '/v1/info'), 200, 'GET /v1/info');
      check(info.setup_done === true, 'setup_done not true after setup');
      const again = await api('POST', '/v1/setup', {
        body: { setup_token: SETUP_TOKEN, sign_pub: b64encode(phone.id.signPub), kx_pub: b64encode(phone.id.kxPub) },
      });
      check(again.status >= 400 && again.status < 500, `second setup should fail, got ${describe(again)}`);
      return `setup ${r.ms.toFixed(0)} ms; second setup -> ${again.status} ${again.body?.error?.code}`;
    }))
  )
    return;

  // 3
  let pairing = null;
  if (
    !(await step('3. Pairing (offer, QR, WS pair frame, accept, welcome, records, dir)', async () => {
      const t = {};
      macWs = new Socket(mac.token, 'mac');
      await macWs.opened;

      const offer = expectStatus(await api('POST', '/v1/pair/offer', { token: mac.token, body: {} }), 200, 'POST /v1/pair/offer');
      check(typeof offer.pair_id === 'string' && offer.expires_at > Date.now(), 'bad offer');
      const pairSecret = defaultRng(32);
      const uri = buildPairUri(HUB, offer.pair_id, pairSecret);

      // Phone scans the QR.
      const qr = parseQrUri(uri);
      check(qr?.kind === 'pair' && qr.hub === HUB && qr.pairId === offer.pair_id, 'pair URI did not round-trip');
      check(equalBytes(qr.pairSecret, pairSecret), 'pair secret did not round-trip');
      const accept = await api('POST', '/v1/pair/accept', { body: buildPairAccept(qr.pairId, qr.pairSecret, phone.id) });
      const acc = expectStatus(accept, 200, 'POST /v1/pair/accept');
      check(acc.device_id === phone.deviceId, 'accept device_id does not match device_id(sign_pub)');
      phone.token = acc.token;
      t.accept = accept.ms;

      // A pending device may only fetch its welcome.
      const early = await api('GET', `/v1/pair/${qr.pairId}/welcome`, { token: phone.token });
      expectStatus(early, 404, 'GET welcome before it is posted');
      const pendingList = await api('GET', '/v1/devices', { token: phone.token });
      expectStatus(pendingList, 403, 'pending device GET /v1/devices');
      const reaccept = await api('POST', '/v1/pair/accept', { body: buildPairAccept(qr.pairId, qr.pairSecret, generateIdentity()) });
      check(reaccept.status >= 400, `second accept should fail, got ${describe(reaccept)}`);

      // Mac gets the candidate and verifies it.
      const frame = await macWs.next((f) => f.t === 'pair', 'pair frame');
      check(frame.pair_id === offer.pair_id, 'pair frame for another pair_id');
      const cand = verifyPairCandidate(offer.pair_id, pairSecret, frame.candidate);
      check(cand.ok, `verifyPairCandidate failed: ${cand.error}`);
      check(cand.deviceId === phone.deviceId, 'candidate is not the phone');
      const polled = expectStatus(await api('GET', `/v1/pair/${offer.pair_id}`, { token: mac.token }), 200, 'GET /v1/pair/:id');
      check(polled.status === 'accepted' && polled.candidate?.device_id === phone.deviceId, `poll status ${polled.status}`);

      const box = sealWelcome({
        pairId: offer.pair_id,
        candidateId: cand.deviceId,
        candidateKxPub: cand.kxPub,
        pairSecret,
        welcome: { keys: [{ kv: 1, k: b64encode(mac.keys.get(1)) }], inviter: mac.deviceId },
      });
      expectStatus(await api('POST', `/v1/pair/${offer.pair_id}/welcome`, { token: mac.token, body: { box } }), 200, 'POST welcome');

      // Phone polls the welcome (every 2 s per SPEC §8).
      let welcome = null;
      for (let i = 0; i < 10 && welcome === null; i++) {
        const r = await api('GET', `/v1/pair/${qr.pairId}/welcome`, { token: phone.token });
        if (r.status === 200) welcome = r.body;
        else {
          check(r.status === 404, `GET welcome: ${describe(r)}`);
          await sleep(2000);
        }
      }
      check(welcome !== null, 'welcome never arrived');
      const opened = openWelcome(welcome.box, qr.pairId, phone.deviceId, phone.id.kxSk, qr.pairSecret);
      check(opened.ok, `openWelcome failed: ${opened.error}`);
      check(opened.welcome.inviter === mac.deviceId, 'welcome inviter is not the Mac');
      for (const k of opened.welcome.keys) phone.keys.set(k.kv, b64decode(k.k));
      phone.kv = Math.max(...phone.keys.keys());
      check(phone.kv === 1 && equalBytes(phone.keys.get(1), mac.keys.get(1)), 'phone got the wrong group key');
      const st = expectStatus(await api('GET', `/v1/pair/${offer.pair_id}`, { token: mac.token }), 200, 'GET /v1/pair/:id');
      check(st.status === 'done', `pairing status after welcome is ${st.status}`);

      // Phone publishes its record; the Mac hears about it.
      macWs.drain();
      const tDir = performance.now();
      await publishRecord(phone);
      await macWs.next((f) => f.t === 'dir', 'dir frame after phone record');
      t.dir = performance.now() - tDir;

      // Both open each other's records and route.
      await refreshDirectory(mac);
      await refreshDirectory(phone);
      for (const dev of [mac, phone]) {
        check(dev.directory.has(mac.deviceId) && dev.directory.has(phone.deviceId), `${dev.name} directory lacks a member`);
      }
      const notifTo = routeRecipients({ type: 'notification.posted@1', senderId: phone.deviceId, directory: [...phone.directory.values()] });
      const clipTo = routeRecipients({ type: 'clipboard.set@1', senderId: mac.deviceId, directory: [...mac.directory.values()] });
      const dismissTo = routeRecipients({ type: 'notification.posted@1', senderId: mac.deviceId, directory: [...mac.directory.values()] });
      check(notifTo.length === 1 && notifTo[0] === mac.deviceId, 'phone routes notifications wrongly');
      check(clipTo.length === 1 && clipTo[0] === phone.deviceId, 'Mac routes clipboard wrongly');
      check(dismissTo.length === 0, 'Mac would send notifications to the phone');
      pairing = offer.pair_id;
      return `accept ${t.accept.toFixed(0)} ms, record->dir ${t.dir.toFixed(0)} ms`;
    }))
  )
    return;

  // 4
  if (
    !(await step('4. Phone -> Mac notification.posted@1 live over WS + ack', async () => {
      const env = seal(phone, 'notification.posted@1', notificationBody('k-live', 'Hello'));
      check(env.collapse !== undefined && env.to.length === 1 && env.to[0] === mac.deviceId, 'bad envelope routing/collapse');
      macWs.drain();
      const t0 = performance.now();
      const posted = postEnvelope(phone, env);
      const frame = await macWs.next((f) => f.t === 'msg' && f.envelope?.msg_id === env.msg_id, 'live msg frame');
      const r = await posted;
      const p = expectStatus(r, 200, 'POST /v1/messages');
      check(Number.isInteger(p.seq) && p.duplicate === false, `unexpected post response ${JSON.stringify(p)}`);
      latency.live = frame.__at - t0;
      latency.livePost = r.ms;
      check(frame.seq === p.seq, 'frame seq differs from post seq');
      const res = receive(mac, frame.envelope);
      check(res.ok, `receiveEnvelope: ${res.error}`);
      check(res.payload.type === 'notification.posted@1' && res.payload.body.title === 'Hello', 'wrong payload');
      macWs.send({ t: 'ack', up_to: frame.seq });
      // The ack frame is fire-and-forget; poll until the Hub reflects it.
      let pending = null;
      for (let i = 0; i < 20; i++) {
        pending = expectStatus(await api('GET', '/v1/messages', { token: mac.token }), 200, 'GET /v1/messages');
        if (pending.messages.length === 0) break;
        await sleep(250);
      }
      check(pending.messages.length === 0, 'message still pending after WS ack');
      liveEnvelope = env;
      return `post->WS receive ${latency.live.toFixed(0)} ms (POST RTT ${latency.livePost.toFixed(0)} ms)`;
    }))
  )
    return;

  // 5
  if (
    !(await step('5. Collapse while Mac offline: posted then removed -> backlog has only removed', async () => {
      const closeMs = await macWs.close();
      if (closeMs === null) notes.push('step 5: the Hub did not complete the WebSocket close handshake within 5 s (socket terminated)');
      staleMsgId = phone.nextMsgId(); // older than the next two, used in step 6
      const a = seal(phone, 'notification.posted@1', notificationBody('k-collapse', 'Will be removed'));
      const b = seal(phone, 'notification.removed@1', { key: 'k-collapse' });
      check(a.collapse === b.collapse, 'posted and removed do not share a collapse slot');
      const ra = expectStatus(await postEnvelope(phone, a), 200, 'POST posted');
      const rb = expectStatus(await postEnvelope(phone, b), 200, 'POST removed');
      check(rb.seq > ra.seq, 'seq did not increase');
      const t0 = performance.now();
      macWs = new Socket(mac.token, 'mac');
      await macWs.opened;
      const frame = await macWs.next((f) => f.t === 'msg', 'backlog msg frame');
      const backlogMs = frame.__at - t0;
      await sleep(1500);
      const extra = macWs.drain().filter((f) => f.t === 'msg');
      check(extra.length === 0, `backlog had ${extra.length + 1} messages, expected 1`);
      check(frame.envelope.msg_id === b.msg_id && frame.seq === rb.seq, 'backlog message is not the removal');
      const res = receive(mac, frame.envelope);
      check(res.ok && res.payload.type === 'notification.removed@1', `receive: ${res.ok ? res.payload.type : res.error}`);
      macWs.send({ t: 'ack', up_to: frame.seq });
      latency.backlog = backlogMs;
      return `close handshake ${closeMs === null ? 'timed out' : `${closeMs.toFixed(0)} ms`}; seqs ${ra.seq}->${rb.seq}; reconnect->backlog frame ${backlogMs.toFixed(0)} ms`;
    }))
  )
    return;

  // 6
  await step('6. Idempotency: duplicate msg_id and superseded older msg_id', async () => {
    const dup = expectStatus(await postEnvelope(phone, liveEnvelope), 200, 'POST duplicate');
    check(dup.duplicate === true && Number.isInteger(dup.seq), `duplicate response ${JSON.stringify(dup)}`);
    const old = seal(phone, 'notification.posted@1', notificationBody('k-collapse', 'Late retry'), { msgId: staleMsgId });
    const sup = expectStatus(await postEnvelope(phone, old), 200, 'POST superseded');
    check(sup.superseded === true && sup.seq === null, `superseded response ${JSON.stringify(sup)}`);
    await sleep(500);
    const leaked = macWs.drain().filter((f) => f.t === 'msg');
    check(leaked.length === 0, 'duplicate/superseded post was delivered');
    return `duplicate seq=${dup.seq}, superseded seq=null`;
  });

  // 7
  await step('7. Mac -> phone clipboard.set@1 via GET /v1/messages + POST /v1/acks', async () => {
    const env = seal(mac, 'clipboard.set@1', { mime: 'text/plain', text: 'smoke clipboard ✓', sensitive: false });
    check(env.to.length === 1 && env.to[0] === phone.deviceId, 'clipboard not routed to the phone');
    const t0 = performance.now();
    const p = expectStatus(await postEnvelope(mac, env), 200, 'POST clipboard');
    const got = expectStatus(await api('GET', '/v1/messages?limit=50', { token: phone.token }), 200, 'GET /v1/messages');
    latency.fetch = performance.now() - t0;
    check(got.messages.length === 1 && got.more === false, `expected 1 message, got ${got.messages.length}`);
    const m = got.messages[0];
    check(m.seq === p.seq, 'seq mismatch');
    const res = receive(phone, m.envelope);
    check(res.ok, `receiveEnvelope: ${res.error}`);
    check(res.payload.body.text === 'smoke clipboard ✓', 'wrong clipboard text');
    expectStatus(await api('POST', '/v1/acks', { token: phone.token, body: { up_to: m.seq } }), 200, 'POST /v1/acks');
    const after = expectStatus(await api('GET', '/v1/messages', { token: phone.token }), 200, 'GET /v1/messages');
    check(after.messages.length === 0, 'messages still pending after ack');
    return `post+fetch ${latency.fetch.toFixed(0)} ms`;
  });

  // 8
  await step('8. Push subscription PUT + DELETE', async () => {
    const kp = await webcrypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
    const p256dh = b64encode(new Uint8Array(await webcrypto.subtle.exportKey('raw', kp.publicKey)));
    const auth = b64encode(defaultRng(16));
    const sub = { endpoint: 'https://push.example.invalid/x', p256dh, auth };
    expectStatus(await api('PUT', '/v1/devices/self/push', { token: phone.token, body: sub }), 200, 'PUT push');
    let list = await refreshDirectory(mac);
    check(list.find((d) => d.id === phone.deviceId)?.has_push === true, 'has_push not true');
    const bad = await api('PUT', '/v1/devices/self/push', { token: phone.token, body: { ...sub, endpoint: 'http://push.example.invalid/x' } });
    expectStatus(bad, 400, 'PUT push with http endpoint');
    expectStatus(await api('DELETE', '/v1/devices/self/push', { token: phone.token }), 200, 'DELETE push');
    list = await refreshDirectory(mac);
    check(list.find((d) => d.id === phone.deviceId)?.has_push === false, 'has_push not false');
    return 'has_push true -> false; http endpoint -> 400';
  });

  // 9
  await step('9. Revoke phone, rekey kv=2, verify keyring', async () => {
    phoneWs = new Socket(phone.token, 'phone');
    await phoneWs.opened;
    macWs.drain();
    expectStatus(await api('DELETE', `/v1/devices/${phone.deviceId}`, { token: mac.token }), 200, 'DELETE /v1/devices/:id');
    await phoneWs.next((f) => f.t === 'revoked', 'revoked frame', 10_000, false);
    for (let i = 0; i < 20 && !phoneWs.closed; i++) await sleep(100);
    check(phoneWs.closed !== null, 'phone socket not closed after revocation');
    await macWs.next((f) => f.t === 'dir', 'dir frame after revocation');
    expectStatus(await api('GET', '/v1/messages', { token: phone.token }), 401, 'revoked phone GET /v1/messages');
    const list = await refreshDirectory(mac);
    check(!list.some((d) => d.id === phone.deviceId), 'revoked phone still listed');

    // Rekey to kv=2 for the remaining members (just the Mac).
    const prevDirectory = trustedKeys(mac);
    const k2 = defaultRng(32);
    const version = buildRekey({ by: mac.id, kv: 2, groupKey: k2, members: [{ id: mac.deviceId, kxPub: mac.id.kxPub }] });
    macWs.drain();
    expectStatus(await api('PUT', '/v1/keyring', { token: mac.token, body: version }), 200, 'PUT /v1/keyring');
    await macWs.next((f) => f.t === 'dir', 'dir frame after rekey');
    const stale = await api('PUT', '/v1/keyring', { token: mac.token, body: version });
    expectStatus(stale, 409, 'PUT /v1/keyring kv=2 again');
    const kr = expectStatus(await api('GET', '/v1/keyring?have=1', { token: mac.token }), 200, 'GET /v1/keyring');
    check(kr.versions.length === 1 && kr.versions[0].kv === 2, `keyring versions ${kr.versions.map((v) => v.kv)}`);
    const opened = openRekey(kr.versions[0], { selfId: mac.deviceId, kxSk: mac.id.kxSk, directory: prevDirectory });
    check(opened.ok, `openRekey: ${opened.error}`);
    check(opened.kv === 2 && equalBytes(opened.groupKey, k2), 'keyring key differs');
    const none = expectStatus(await api('GET', '/v1/keyring?have=2', { token: mac.token }), 200, 'GET /v1/keyring?have=2');
    check(none.versions.length === 0, 'have=2 returned versions');
    mac.keys.set(2, k2);
    mac.kv = 2;
    await publishRecord(mac);
    await refreshDirectory(mac);
    check(mac.directory.size === 1 && mac.directory.has(mac.deviceId), 'Mac record not readable under kv=2');
    return 'revoked frame + close code ' + phoneWs.closed.code + ', 401 after revoke, kv=2 verified';
  });

  // 10
  await step('10. Negative checks: bad token 401, tampered sig 400, from mismatch 403', async () => {
    const fake = `ct1.${mac.deviceId}.${b64encode(new Uint8Array(32))}.${b64encode(new Uint8Array(16))}`;
    expectStatus(await api('GET', '/v1/devices', { token: fake }), 401, 'bad token');
    expectStatus(await api('GET', '/v1/devices'), 401, 'no token');
    const other = generateIdentity();
    const target = [other.deviceId];

    const env = seal(mac, 'clipboard.set@1', { mime: 'text/plain', text: 'x', sensitive: false }, { to: target });
    const sig = b64decode(env.sig);
    sig[40] ^= 0x01;
    expectStatus(await postEnvelope(mac, { ...env, sig: b64encode(sig) }), 400, 'tampered sig');
    const ct = b64decode(env.ct);
    ct[0] ^= 0x01;
    expectStatus(await postEnvelope(mac, { ...env, ct: b64encode(ct) }), 400, 'tampered ct');

    // Signed correctly, but claims to come from another device.
    const spoof = sealEnvelope({
      msg_id: mac.nextMsgId(),
      from: other.deviceId,
      to: [mac.deviceId],
      ttl_s: 3600,
      plaintext: encodePayload({ type: 'clipboard.set@1', ts: Date.now(), body: { mime: 'text/plain', text: 'x', sensitive: false } }),
      groupKey: mac.keys.get(mac.kv),
      kv: mac.kv,
      signSk: mac.id.signSk,
    });
    expectStatus(await postEnvelope(mac, spoof), 403, 'from mismatch');

    // A valid envelope to a device that is not active is a 409 listing it.
    const r = await postEnvelope(mac, env);
    const c = expectStatus(r, 409, 'unknown recipient');
    check(Array.isArray(c.inactive) && c.inactive[0] === other.deviceId, `409 body ${JSON.stringify(c)}`);
    return '401/401/400/400/403/409 as expected';
  });
}

async function cleanup() {
  for (const s of [macWs, phoneWs]) if (s) await s.close().catch(() => {});
  await step('11. POST /v1/reset and confirm setup_done=false', async () => {
    const r = await api('POST', '/v1/reset', { body: { setup_token: SETUP_TOKEN, confirm: 'erase everything' } });
    expectStatus(r, 200, 'POST /v1/reset');
    const info = expectStatus(await api('GET', '/v1/info'), 200, 'GET /v1/info');
    check(info.setup_done === false, 'setup_done still true after reset');
    if (mac.token) expectStatus(await api('GET', '/v1/devices', { token: mac.token }), 401, 'old Mac token after reset');
    return 'hub unclaimed';
  });
}

const tStart = performance.now();
console.log(`Carom Hub smoke test against ${HUB}`);
try {
  await run();
} catch (err) {
  console.log(`FAIL  unexpected error: ${redact(err?.stack ?? err)}`);
  results.push({ name: 'unexpected', ok: false });
} finally {
  await cleanup();
}
const failed = results.filter((r) => !r.ok);
const lat = Object.entries(latency)
  .map(([k, v]) => `${k}=${v.toFixed(0)}ms`)
  .join(' ');
console.log(
  `\n${failed.length === 0 ? 'ALL PASS' : `${failed.length} FAILED`}: ${results.length - failed.length}/${results.length} steps in ${(
    (performance.now() - tStart) /
    1000
  ).toFixed(1)} s${lat ? `; latency ${lat}` : ''}`,
);
for (const n of notes) console.log(`note: ${n}`);
process.exit(failed.length === 0 ? 0 : 1);
