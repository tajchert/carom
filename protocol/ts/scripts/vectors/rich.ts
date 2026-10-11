// Message body vectors (SPEC §4): default TTLs, reply_to requirements, collapse keys, bodies
// every port must accept (all V2 fields), and bodies it must reject as bad_payload. The first
// four payloads are also sealed in envelope_seal.json under the labels named below.

import {
  collapseRealKey,
  DEFAULT_TTL_S,
  isValidBody,
  MESSAGE_TYPES,
  requiresReplyTo,
  type MediaCommandBody,
  type MediaStateBody,
  type MessageBodies,
  type MessageType,
  type NotificationActionBody,
  type NotificationActionResultBody,
  type NotificationPostedBody,
} from '../../src/messages.ts';
import type { Payload } from '../../src/payload.ts';
import { AVATAR_REF, ICON_REF, PHOTO_REF, SMALL_ICON_REF } from './blob.ts';
import { b64, msgId, T0, type VectorFile } from './fixtures.ts';

export const RICH_KEY = '0|com.whatsapp|2|family|10124';
export const MEDIA_SESSION = 'com.spotify.music/7f3a9c';
const RICH_TS = T0 + 20_000;

/** Sealed in envelope_seal.json with label "rich-posted". */
export const RICH_POSTED: Payload<NotificationPostedBody> = {
  type: 'notification.posted@1',
  ts: RICH_TS,
  body: {
    key: RICH_KEY,
    app: { id: 'com.whatsapp', name: 'WhatsApp', icon: ICON_REF },
    title: 'Family',
    text: 'Mum: See you at 8',
    category: 'msg',
    posted_at: RICH_TS - 400,
    redacted: false,
    template: 'messaging',
    color: 0xff00a884 | 0,
    small_icon: SMALL_ICON_REF,
    large_icon: AVATAR_REF,
    summary_text: '2 new messages',
    conversation: { title: 'Family', is_group: true },
    messages: [
      { sender: { name: 'Anna', key: 'anna', icon: AVATAR_REF }, text: 'Dinner at 8? 🍝', ts: RICH_TS - 60_000 },
      { sender: { name: 'Mum' }, text: 'See you at 8', ts: RICH_TS - 400, image: PHOTO_REF },
    ],
    actions: [
      { index: 0, title: 'Reply', semantic: 1, reply: { label: 'Message', choices: ['On my way', '👍'] } },
      { index: 1, title: 'Mark as read', semantic: 2 },
    ],
    when: RICH_TS - 400,
    show_when: true,
    group: { key: 'g:family', summary: false, sort_key: '0002', alert: 'all' },
    channel: { id: 'group_chat_defaults', importance: 4 },
  },
};

/** Sealed with label "action-reply". */
export const ACTION_REPLY: Payload<NotificationActionBody> = {
  type: 'notification.action@1',
  ts: T0 + 30_000,
  reply_to: msgId('rich-posted', RICH_POSTED.ts),
  body: { key: RICH_KEY, action: { index: 0, title: 'Reply', reply_text: 'On my way 🚗' } },
};

/** Sealed with label "media-state". */
export const MEDIA_STATE: Payload<MediaStateBody> = {
  type: 'media.state@1',
  ts: T0 + 40_000,
  body: {
    session: MEDIA_SESSION,
    app: { id: 'com.spotify.music', name: 'Spotify', icon: ICON_REF },
    title: 'Clair de Lune',
    artist: 'Claude Debussy',
    album: 'Suite bergamasque',
    art: PHOTO_REF,
    state: 'playing',
    position_ms: 61_000,
    position_at: T0 + 39_500,
    duration_ms: 302_000,
    controls: ['play', 'pause', 'next', 'previous', 'seek'],
  },
};

/** Sealed with label "media-command". */
export const MEDIA_COMMAND: Payload<MediaCommandBody> = {
  type: 'media.command@1',
  ts: T0 + 45_000,
  reply_to: msgId('media-state', MEDIA_STATE.ts),
  body: { session: MEDIA_SESSION, command: 'seek', position_ms: 90_000 },
};

const ACTION_RESULT: Payload<NotificationActionResultBody> = {
  type: 'notification.action_result@1',
  ts: T0 + 31_000,
  reply_to: msgId('action-reply', ACTION_REPLY.ts),
  body: { ok: false, error: 'changed' },
};

const LIVE_UPDATE: Payload<NotificationPostedBody> = {
  type: 'notification.posted@1',
  ts: T0 + 50_000,
  body: {
    key: '0|com.ubercab|7|null|10200',
    app: { id: 'com.ubercab', name: 'Uber' },
    title: 'Driver arriving',
    text: 'Toyota Prius · KX 4821',
    posted_at: T0 + 49_000,
    redacted: false,
    ongoing: true,
    template: 'progress',
    color: 0xff000000 | 0,
    colorized: false,
    progress: {
      value: 60,
      max: 100,
      indeterminate: false,
      segments: [{ length: 50, color: 0xff2e7d32 | 0 }, { length: 50 }],
      points: [{ position: 50, color: 0xffffffff | 0 }],
      tracker_icon: SMALL_ICON_REF,
    },
    live: { promoted: true, short_critical_text: '3 min' },
    when: T0 + 230_000,
    show_when: true,
    chronometer: { count_down: true },
    channel: { id: 'trip_status', importance: 3 },
  },
};

const INCOMING_CALL: Payload<NotificationPostedBody> = {
  type: 'notification.posted@1',
  ts: T0 + 60_000,
  body: {
    key: '0|com.google.android.dialer|1|null|10300',
    app: { id: 'com.google.android.dialer', name: 'Phone' },
    title: 'Anna',
    text: 'Incoming call',
    category: 'call',
    posted_at: T0 + 59_900,
    redacted: false,
    ongoing: true,
    template: 'call',
    call: { type: 'incoming', person: { name: 'Anna', icon: AVATAR_REF }, is_video: false, verification: 'Verified business' },
    actions: [
      { index: 0, title: 'Decline' },
      { index: 1, title: 'Answer' },
    ],
  },
};

const HIDDEN: Payload<NotificationPostedBody> = {
  type: 'notification.posted@1',
  ts: T0 + 70_000,
  body: { key: RICH_KEY, app: { id: 'com.whatsapp', name: 'WhatsApp', icon: ICON_REF }, posted_at: T0 + 69_000, redacted: true },
};

const MIN_POSTED = { key: 'k', app: { id: 'a', name: 'A' }, posted_at: T0, redacted: false };
const MEDIA_MIN = { session: 's', app: { id: 'a', name: 'A' }, state: 'playing', controls: ['play'] };

export function messageVectors(): VectorFile {
  const valid: Array<{ name: string; payload: Payload<unknown> }> = [
    { name: 'messaging notification with blob references, actions, group and channel', payload: RICH_POSTED },
    { name: 'Live Update with segmented progress, points, chronometer and promoted live', payload: LIVE_UPDATE },
    { name: 'incoming call', payload: INCOMING_CALL },
    { name: 'Hide content mode: key, app with icon, posted_at, redacted', payload: HIDDEN },
    { name: 'inline reply action', payload: ACTION_REPLY },
    { name: 'answer action', payload: { type: 'notification.action@1', ts: T0 + 61_000, reply_to: msgId('incoming-call', INCOMING_CALL.ts), body: { key: INCOMING_CALL.body.key, action: 'answer' } } },
    { name: 'failed action result', payload: ACTION_RESULT },
    { name: 'successful action result', payload: { type: 'notification.action_result@1', ts: T0 + 31_500, reply_to: ACTION_RESULT.reply_to!, body: { ok: true } } },
    { name: 'media state with album art', payload: MEDIA_STATE },
    { name: 'stopped media session with only the required fields', payload: { type: 'media.state@1', ts: T0 + 80_000, body: { session: MEDIA_SESSION, app: { id: 'com.spotify.music', name: 'Spotify' }, state: 'stopped', controls: [] } } },
    { name: 'seek command', payload: MEDIA_COMMAND },
    { name: 'next command', payload: { type: 'media.command@1', ts: T0 + 46_000, reply_to: MEDIA_COMMAND.reply_to!, body: { session: MEDIA_SESSION, command: 'next' } } },
  ];
  const invalid: Array<[string, MessageType, Record<string, unknown>]> = [
    ['color is not an integer', 'notification.posted@1', { ...MIN_POSTED, color: 1.5 }],
    ['color written unsigned (outside int32)', 'notification.posted@1', { ...MIN_POSTED, color: 0xff00a884 }],
    ['8 lines', 'notification.posted@1', { ...MIN_POSTED, lines: Array(8).fill('line') }],
    ['lines contains a number', 'notification.posted@1', { ...MIN_POSTED, lines: ['a', 1] }],
    ['7 messages', 'notification.posted@1', { ...MIN_POSTED, messages: Array.from({ length: 7 }, (_, i) => ({ text: `m${i}`, ts: T0 })) }],
    ['message without text', 'notification.posted@1', { ...MIN_POSTED, messages: [{ ts: T0 }] }],
    ['message sender without name', 'notification.posted@1', { ...MIN_POSTED, messages: [{ sender: { key: 'anna' }, text: 'hi', ts: T0 }] }],
    ['7 actions', 'notification.posted@1', { ...MIN_POSTED, actions: Array.from({ length: 7 }, (_, i) => ({ index: i, title: `a${i}` })) }],
    ['action without title', 'notification.posted@1', { ...MIN_POSTED, actions: [{ index: 0 }] }],
    ['negative action index', 'notification.posted@1', { ...MIN_POSTED, actions: [{ index: -1, title: 'x' }] }],
    ['conversation without is_group', 'notification.posted@1', { ...MIN_POSTED, conversation: { title: 'Family' } }],
    ['group without alert', 'notification.posted@1', { ...MIN_POSTED, group: { key: 'g', summary: false } }],
    ['channel without importance', 'notification.posted@1', { ...MIN_POSTED, channel: { id: 'c' } }],
    ['progress with 11 segments', 'notification.posted@1', { ...MIN_POSTED, progress: { value: 0, max: 11, indeterminate: false, segments: Array.from({ length: 11 }, () => ({ length: 1 })) } }],
    ['progress with 5 points', 'notification.posted@1', { ...MIN_POSTED, progress: { value: 0, max: 5, indeterminate: false, points: Array.from({ length: 5 }, (_, i) => ({ position: i })) } }],
    ['progress without indeterminate', 'notification.posted@1', { ...MIN_POSTED, progress: { value: 1, max: 2 } }],
    ['live without promoted', 'notification.posted@1', { ...MIN_POSTED, live: {} }],
    ['call without type', 'notification.posted@1', { ...MIN_POSTED, call: {} }],
    ['blob reference id of 16 bytes', 'notification.posted@1', { ...MIN_POSTED, large_icon: { ...AVATAR_REF, id: b64(new Uint8Array(16)) } }],
    ['blob reference without hash', 'notification.posted@1', { ...MIN_POSTED, large_icon: { id: AVATAR_REF.id, key: AVATAR_REF.key, mime: AVATAR_REF.mime, size: AVATAR_REF.size } }],
    ['blob reference key with padding', 'notification.posted@1', { ...MIN_POSTED, large_icon: { ...AVATAR_REF, key: AVATAR_REF.key + '=' } }],
    ['app icon is not a blob reference', 'notification.posted@1', { ...MIN_POSTED, app: { id: 'a', name: 'A', icon: 'icon.png' } }],
    ['unknown fixed action', 'notification.action@1', { key: 'k', action: 'press' }],
    ['button without title', 'notification.action@1', { key: 'k', action: { index: 0 } }],
    ['reply_text is not a string', 'notification.action@1', { key: 'k', action: { index: 0, title: 'Reply', reply_text: 5 } }],
    ['action without key', 'notification.action@1', { action: 'answer' }],
    ['the removed tap action', 'notification.action@1', { key: 'k', action: 'tap' }],
    ['result without ok', 'notification.action_result@1', { error: 'gone' }],
    ['result error is not a string', 'notification.action_result@1', { ok: false, error: 1 }],
    ['media state without controls', 'media.state@1', { session: 's', app: { id: 'a', name: 'A' }, state: 'playing' }],
    ['media state without app name', 'media.state@1', { ...MEDIA_MIN, app: { id: 'a' } }],
    ['media state with an empty session', 'media.state@1', { ...MEDIA_MIN, session: '' }],
    ['media state controls are not strings', 'media.state@1', { ...MEDIA_MIN, controls: [1] }],
    ['unknown media command', 'media.command@1', { session: 's', command: 'stop' }],
    ['seek without position_ms', 'media.command@1', { session: 's', command: 'seek' }],
    ['negative position_ms', 'media.command@1', { session: 's', command: 'seek', position_ms: -1 }],
    ['media command without session', 'media.command@1', { command: 'play' }],
  ];
  const missingReply: Array<{ name: string; payload: Payload<unknown> }> = [
    { name: 'notification.dismiss@1 without reply_to', payload: { type: 'notification.dismiss@1', ts: T0, body: { key: RICH_KEY } } },
    { name: 'notification.action@1 without reply_to', payload: { type: 'notification.action@1', ts: T0, body: { key: RICH_KEY, action: 'answer' } } },
    { name: 'notification.action_result@1 without reply_to', payload: { type: 'notification.action_result@1', ts: T0, body: { ok: true } } },
    { name: 'media.command@1 without reply_to', payload: { type: 'media.command@1', ts: T0, body: { session: MEDIA_SESSION, command: 'play' } } },
  ];
  const collapseCases: Array<{ type: MessageType; body: unknown }> = [
    { type: 'notification.posted@1', body: RICH_POSTED.body },
    { type: 'notification.removed@1', body: { key: RICH_KEY } },
    { type: 'notification.dismiss@1', body: { key: RICH_KEY } },
    { type: 'clipboard.set@1', body: { mime: 'text/plain', text: 'x', sensitive: false } },
    { type: 'device.status@1', body: { battery: 50 } },
    { type: 'notification.action@1', body: ACTION_REPLY.body },
    { type: 'notification.action_result@1', body: ACTION_RESULT.body },
    { type: 'media.state@1', body: MEDIA_STATE.body },
    { type: 'media.command@1', body: MEDIA_COMMAND.body },
  ];

  for (const c of valid) {
    if (!isValidBody(c.payload.type as MessageType, c.payload.body)) throw new Error(`valid body rejected: ${c.name}`);
    if (requiresReplyTo(c.payload.type as MessageType) && c.payload.reply_to === undefined) throw new Error(`valid payload lacks reply_to: ${c.name}`);
  }
  for (const [name, type, body] of invalid) if (isValidBody(type, body)) throw new Error(`invalid body accepted: ${name}`);
  for (const c of missingReply) {
    if (!isValidBody(c.payload.type as MessageType, c.payload.body) || !requiresReplyTo(c.payload.type as MessageType)) {
      throw new Error(`missing_reply_to case is wrong: ${c.name}`);
    }
  }

  return {
    description:
      'Message bodies (SPEC §4). defaults gives each type\'s default TTL in seconds and whether a payload-level reply_to is required (a missing one is bad_payload, SPEC §3.3 rule 5). collapse_cases gives the collapse real key for a body, or null when the type does not collapse. valid payloads MUST be accepted and parsed, including every V2 field of notification.posted@1. invalid_bodies MUST be rejected as bad_payload. missing_reply_to payloads have a valid body but lack the required reply_to. Integers marked int32 in SPEC §4 are signed 32-bit, so ARGB colours with the alpha bit set are negative. Blob references are the ones sealed in blob.json. The first valid payload and the inline reply, media state and seek command are also sealed in envelope_seal.json.',
    defaults: MESSAGE_TYPES.map((type) => ({ type, default_ttl_s: DEFAULT_TTL_S[type], reply_to_required: requiresReplyTo(type) })),
    collapse_cases: collapseCases.map((c) => ({ type: c.type, body: c.body, real_key: collapseRealKey(c.type, c.body as MessageBodies[MessageType]) })),
    valid,
    invalid_bodies: invalid.map(([name, type, body]) => ({ name, type, body })),
    missing_reply_to: missingReply,
  };
}
