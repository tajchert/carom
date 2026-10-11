// Receiver rules (SPEC §3.3), applied in order. Every failure means: drop the message
// silently, log locally, and still acknowledge it.

import type { Bytes } from './bytes.ts';
import { LIMITS } from './constants.ts';
import { deviceIdMatches } from './device.ts';
import { openEnvelope, type GroupKeys } from './envelope.ts';
import { validateEnvelopeShape, type Envelope } from './envelope-shape.ts';
import { isMessageType, isValidBody, MESSAGE_TYPES, requiresReplyTo } from './messages.ts';
import type { Payload } from './payload.ts';

export type ReceiveError =
  | 'bad_shape'
  | 'not_addressed'
  | 'untrusted_sender'
  | 'bad_signature'
  | 'unknown_kv'
  | 'decrypt_failed'
  | 'bad_payload'
  | 'expired'
  | 'from_future'
  | 'replay'
  | 'unknown_type'
  | 'stale_clipboard'
  | 'reply_not_allowed';

export interface ReceiveContext {
  selfId: string;
  /** Trusted directory (SPEC §5.3): member id → sign_pub. */
  directory: ReadonlyMap<string, Bytes>;
  keys: GroupKeys;
  /** Receiver clock, Unix ms. */
  now: number;
  /** True when this msg_id was already seen (kept 7 days plus skew). */
  hasSeen: (msgId: string) => boolean;
  /** Types this device handles (its `caps`). Defaults to every v1 type. */
  knownTypes?: ReadonlySet<string>;
  /** `ts` of the last clipboard item applied locally. */
  lastClipboardTs?: number;
  /** `to` of a message this device sent, by msg_id; undefined when unknown. */
  originalRecipients?: (msgId: string) => readonly string[] | undefined;
}

export type ReceiveResult = { ok: true; envelope: Envelope; payload: Payload } | { ok: false; error: ReceiveError };

/** Rule 1a: the receiver's own id is in `to`. */
export function isAddressedTo(env: Envelope, selfId: string): boolean {
  return env.to.includes(selfId);
}

/** Rule 1b: `from` is a trusted member and `device_id(sign_pub)` matches. Returns its key. */
export function trustedSenderKey(directory: ReadonlyMap<string, Bytes>, from: string): Bytes | null {
  const signPub = directory.get(from);
  return signPub !== undefined && deviceIdMatches(from, signPub) ? signPub : null;
}

/**
 * Rule 3: `now - ts <= ttl_s*1000 + 300000` and `ts - now <= 300000`. Both bounds are inclusive.
 */
export function checkFreshness(ts: number, ttlS: number, now: number): 'expired' | 'from_future' | null {
  if (now - ts > ttlS * 1000 + LIMITS.clockSkewMs) return 'expired';
  if (ts - now > LIMITS.clockSkewMs) return 'from_future';
  return null;
}

/** Rule 6: a clipboard item older than the last one applied is ignored. Equal ts is accepted. */
export function isStaleClipboard(ts: number, lastClipboardTs: number | undefined): boolean {
  return lastClipboardTs !== undefined && ts < lastClipboardTs;
}

/** Rule 7: a reply is accepted only from a recipient of the original message. */
export function isReplyAllowed(from: string, originalTo: readonly string[] | undefined): boolean {
  return originalTo !== undefined && originalTo.includes(from);
}

const DEFAULT_KNOWN_TYPES: ReadonlySet<string> = new Set(MESSAGE_TYPES);

/** Applies SPEC §3.3 rules 1–7 in order and returns the first failure, or the payload. */
export function receiveEnvelope(value: unknown, ctx: ReceiveContext): ReceiveResult {
  const shape = validateEnvelopeShape(value);
  if (!shape.ok) return { ok: false, error: 'bad_shape' };
  const env = shape.envelope;

  if (!isAddressedTo(env, ctx.selfId)) return { ok: false, error: 'not_addressed' };
  const signPub = trustedSenderKey(ctx.directory, env.from);
  if (signPub === null) return { ok: false, error: 'untrusted_sender' };

  const opened = openEnvelope(env, signPub, ctx.keys);
  if (!opened.ok) return opened;
  const payload = opened.payload;

  const freshness = checkFreshness(payload.ts, env.ttl_s, ctx.now);
  if (freshness !== null) return { ok: false, error: freshness };

  if (ctx.hasSeen(env.msg_id)) return { ok: false, error: 'replay' };

  const known = ctx.knownTypes ?? DEFAULT_KNOWN_TYPES;
  if (!known.has(payload.type)) return { ok: false, error: 'unknown_type' };
  if (isMessageType(payload.type)) {
    if (!isValidBody(payload.type, payload.body)) return { ok: false, error: 'bad_payload' };
    if (requiresReplyTo(payload.type) && payload.reply_to === undefined) {
      return { ok: false, error: 'bad_payload' };
    }
  }

  if (payload.type === 'clipboard.set@1' && isStaleClipboard(payload.ts, ctx.lastClipboardTs)) {
    return { ok: false, error: 'stale_clipboard' };
  }

  if (payload.reply_to !== undefined && !isReplyAllowed(env.from, ctx.originalRecipients?.(payload.reply_to))) {
    return { ok: false, error: 'reply_not_allowed' };
  }

  return { ok: true, envelope: env, payload };
}
