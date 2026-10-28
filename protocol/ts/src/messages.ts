// Message types and their bodies (SPEC §4). Receivers ignore unknown fields, so validators
// only check the fields they know. V2 added optional fields to notification.posted@1 (§4.1)
// and four types (§4.2, §4.3) without bumping any @major.

import { isValidBlobRef, type BlobRef } from './blob.ts';

export const MESSAGE_TYPES = [
  'notification.posted@1',
  'notification.removed@1',
  'notification.dismiss@1',
  'clipboard.set@1',
  'device.status@1',
  'notification.action@1',
  'notification.action_result@1',
  'media.state@1',
  'media.command@1',
  'notification.resync@1',
] as const;

export type MessageType = (typeof MESSAGE_TYPES)[number];

/** List caps from SPEC §4.1. Senders trim to them; a longer list fails the body check. */
export const LIST_LIMITS = { lines: 7, messages: 6, actions: 6, segments: 10, points: 4 } as const;

export interface AppInfo {
  /** Package name. */
  id: string;
  name: string;
  /** Convergent-key blob (SPEC §11.2). */
  icon?: BlobRef;
}

export interface Person {
  name: string;
  key?: string;
  icon?: BlobRef;
}

export interface Conversation {
  title?: string;
  is_group: boolean;
  icon?: BlobRef;
}

export interface ConversationMessage {
  sender?: Person;
  text: string;
  ts: number;
  image?: BlobRef;
}

export interface ReplySpec {
  label?: string;
  choices?: string[];
}

/** One of the app's buttons. `index` is its position in `Notification.actions`. */
export interface ActionSpec {
  index: number;
  title: string;
  /** `Notification.Action.SEMANTIC_ACTION_*`. */
  semantic?: number;
  reply?: ReplySpec;
}

export interface ProgressSegment {
  length: number;
  color?: number;
}

export interface ProgressPoint {
  position: number;
  color?: number;
}

export interface Progress {
  value: number;
  max: number;
  indeterminate: boolean;
  segments?: ProgressSegment[];
  points?: ProgressPoint[];
  tracker_icon?: BlobRef;
  start_icon?: BlobRef;
  end_icon?: BlobRef;
}

export interface Live {
  promoted: boolean;
  short_critical_text?: string;
}

export interface Chronometer {
  count_down: boolean;
}

/** `type` is incoming, ongoing, screening or unknown; receivers treat other values as unknown. */
export interface CallInfo {
  type: string;
  person?: Person;
  is_video?: boolean;
  verification?: string;
}

/** `alert` is all, summary or children; receivers treat other values as all. */
export interface GroupInfo {
  key: string;
  summary: boolean;
  sort_key?: string;
  alert: string;
}

export interface ChannelInfo {
  id: string;
  /** `NotificationManager.IMPORTANCE_*`. */
  importance: number;
}

export interface NotificationPostedBody {
  key: string;
  app: AppInfo;
  title?: string;
  text?: string;
  sub_text?: string;
  category?: string;
  posted_at: number;
  redacted: boolean;
  ongoing?: boolean;
  /** standard, big_text, inbox, messaging, big_picture, progress, call, media or custom. */
  template?: string;
  /** `Notification.color`, ARGB as a signed 32-bit integer. */
  color?: number;
  colorized?: boolean;
  small_icon?: BlobRef;
  large_icon?: BlobRef;
  picture?: BlobRef;
  picture_desc?: string;
  big_text?: string;
  lines?: string[];
  summary_text?: string;
  conversation?: Conversation;
  /** Newest last. */
  messages?: ConversationMessage[];
  actions?: ActionSpec[];
  progress?: Progress;
  live?: Live;
  when?: number;
  show_when?: boolean;
  chronometer?: Chronometer;
  call?: CallInfo;
  group?: GroupInfo;
  channel?: ChannelInfo;
  /** True on a copy sent in answer to `notification.resync@1`. Receivers never alert for it. */
  resync?: boolean;
}

export interface NotificationRemovedBody {
  key: string;
}

/** Sent with payload-level `reply_to` = the posted message's msg_id. */
export interface NotificationDismissBody {
  key: string;
}

/** Sent by a desktop to a phone; asks it to re-send what it currently shows (SPEC §4.4). */
export type NotificationResyncBody = Record<string, never>;

export interface ClipboardSetBody {
  mime: 'text/plain';
  text: string;
  sensitive: boolean;
  source?: string;
}

export interface DeviceStatusBody {
  battery?: number;
  charging?: boolean;
}

/** SPEC §4.2: a fixed action, or the app's button at `index` that must still have `title`. */
export type ActionRef = 'answer' | 'decline' | { index: number; title: string; reply_text?: string };

/** Sent with payload-level `reply_to` = the posted message's msg_id. */
export interface NotificationActionBody {
  key: string;
  action: ActionRef;
}

/** Sent with `reply_to` = the action's msg_id. `error`: gone, changed, blocked or failed. */
export interface NotificationActionResultBody {
  ok: boolean;
  error?: string;
}

/** `state`: playing, paused, buffering or stopped. `controls` from MEDIA_COMMANDS. */
export interface MediaStateBody {
  session: string;
  app: AppInfo;
  title?: string;
  artist?: string;
  album?: string;
  art?: BlobRef;
  state: string;
  position_ms?: number;
  position_at?: number;
  duration_ms?: number;
  controls: string[];
}

export const MEDIA_COMMANDS = ['play', 'pause', 'next', 'previous', 'seek'] as const;

export type MediaCommand = (typeof MEDIA_COMMANDS)[number];

/** Sent with `reply_to` = a media.state msg_id of the same session. `seek` needs `position_ms`. */
export interface MediaCommandBody {
  session: string;
  command: MediaCommand;
  position_ms?: number;
}

export interface MessageBodies {
  'notification.posted@1': NotificationPostedBody;
  'notification.removed@1': NotificationRemovedBody;
  'notification.dismiss@1': NotificationDismissBody;
  'clipboard.set@1': ClipboardSetBody;
  'device.status@1': DeviceStatusBody;
  'notification.action@1': NotificationActionBody;
  'notification.action_result@1': NotificationActionResultBody;
  'media.state@1': MediaStateBody;
  'media.command@1': MediaCommandBody;
  'notification.resync@1': NotificationResyncBody;
}

const DAY_S = 86_400;
const ACTION_TTL_S = 600;

export const DEFAULT_TTL_S: Record<MessageType, number> = {
  'notification.posted@1': 2 * DAY_S,
  'notification.removed@1': 2 * DAY_S,
  'notification.dismiss@1': DAY_S,
  'clipboard.set@1': DAY_S,
  'device.status@1': DAY_S,
  'notification.action@1': ACTION_TTL_S,
  'notification.action_result@1': ACTION_TTL_S,
  'media.state@1': DAY_S,
  'media.command@1': ACTION_TTL_S,
  'notification.resync@1': DAY_S,
};

const REPLY_TYPES: ReadonlySet<MessageType> = new Set<MessageType>([
  'notification.dismiss@1',
  'notification.action@1',
  'notification.action_result@1',
  'media.command@1',
]);

export function isMessageType(t: unknown): t is MessageType {
  return typeof t === 'string' && (MESSAGE_TYPES as readonly string[]).includes(t);
}

/** True for types that MUST carry a payload-level `reply_to` (SPEC §3.3 rules 5 and 7). */
export function requiresReplyTo(type: MessageType): boolean {
  return REPLY_TYPES.has(type);
}

/** The collapse real key for a message (SPEC §3.2), or null when the type does not collapse. */
export function collapseRealKey<T extends MessageType>(type: T, body: MessageBodies[T]): string | null {
  switch (type) {
    case 'notification.posted@1':
    case 'notification.removed@1':
      return `n:${(body as NotificationRemovedBody).key}`;
    case 'clipboard.set@1':
      return 'clip';
    case 'device.status@1':
      return 'status';
    case 'notification.resync@1':
      return 'resync';
    case 'media.state@1':
      return `m:${(body as MediaStateBody).session}`;
    default:
      return null;
  }
}

type Obj = Record<string, unknown>;
type Check = (v: unknown) => boolean;

function isObj(v: unknown): v is Obj {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}
const isStr = (v: unknown): v is string => typeof v === 'string';
const isNonEmptyStr = (v: unknown): boolean => isStr(v) && v.length > 0;
const isBool = (v: unknown): v is boolean => typeof v === 'boolean';
const isMs = (v: unknown): boolean => Number.isSafeInteger(v) && (v as number) >= 0;
const isInt32 = (v: unknown): boolean => Number.isInteger(v) && (v as number) >= -2_147_483_648 && (v as number) <= 2_147_483_647;
const isCount = (v: unknown): boolean => isInt32(v) && (v as number) >= 0;

const opt =
  (check: Check): Check =>
  (v) =>
    v === undefined || check(v);
const listOf =
  (max: number, item: Check): Check =>
  (v) =>
    Array.isArray(v) && v.length <= max && v.every((x) => item(x));
const objWith =
  (check: (o: Obj) => boolean): Check =>
  (v) =>
    isObj(v) && check(v);

const optStr = opt(isStr);
const optBool = opt(isBool);
const optMs = opt(isMs);
const optInt32 = opt(isInt32);
const optCount = opt(isCount);
const optBlob = opt(isValidBlobRef);
const isStrList = listOf(Number.MAX_SAFE_INTEGER, isStr);

const isApp = objWith((a) => isStr(a.id) && isStr(a.name) && optBlob(a.icon));
const isPerson = objWith((p) => isStr(p.name) && optStr(p.key) && optBlob(p.icon));
const isConversation = objWith((c) => optStr(c.title) && isBool(c.is_group) && optBlob(c.icon));
const isConversationMessage = objWith((m) => opt(isPerson)(m.sender) && isStr(m.text) && isMs(m.ts) && optBlob(m.image));
const isReplySpec = objWith((r) => optStr(r.label) && opt(isStrList)(r.choices));
const isActionSpec = objWith((a) => isCount(a.index) && isStr(a.title) && optCount(a.semantic) && opt(isReplySpec)(a.reply));
const isSegment = objWith((s) => isCount(s.length) && optInt32(s.color));
const isPoint = objWith((p) => isCount(p.position) && optInt32(p.color));
const isProgress = objWith(
  (p) =>
    isCount(p.value) &&
    isCount(p.max) &&
    isBool(p.indeterminate) &&
    opt(listOf(LIST_LIMITS.segments, isSegment))(p.segments) &&
    opt(listOf(LIST_LIMITS.points, isPoint))(p.points) &&
    optBlob(p.tracker_icon) &&
    optBlob(p.start_icon) &&
    optBlob(p.end_icon),
);
const isLive = objWith((l) => isBool(l.promoted) && optStr(l.short_critical_text));
const isChronometer = objWith((c) => isBool(c.count_down));
const isCall = objWith((c) => isStr(c.type) && opt(isPerson)(c.person) && optBool(c.is_video) && optStr(c.verification));
const isGroup = objWith((g) => isStr(g.key) && isBool(g.summary) && optStr(g.sort_key) && isStr(g.alert));
const isChannel = objWith((c) => isStr(c.id) && isInt32(c.importance));

const FIXED_ACTIONS: ReadonlySet<string> = new Set(['answer', 'decline']);
const COMMANDS: ReadonlySet<string> = new Set(MEDIA_COMMANDS);

const isActionRef: Check = (v) =>
  (isStr(v) && FIXED_ACTIONS.has(v)) || (isObj(v) && isCount(v.index) && isStr(v.title) && optStr(v.reply_text));

const BODY_VALIDATORS: Record<MessageType, (b: Obj) => boolean> = {
  'notification.posted@1': (b) =>
    isStr(b.key) &&
    isApp(b.app) &&
    optStr(b.title) &&
    optStr(b.text) &&
    optStr(b.sub_text) &&
    optStr(b.category) &&
    isMs(b.posted_at) &&
    isBool(b.redacted) &&
    optBool(b.ongoing) &&
    optStr(b.template) &&
    optInt32(b.color) &&
    optBool(b.colorized) &&
    optBlob(b.small_icon) &&
    optBlob(b.large_icon) &&
    optBlob(b.picture) &&
    optStr(b.picture_desc) &&
    optStr(b.big_text) &&
    opt(listOf(LIST_LIMITS.lines, isStr))(b.lines) &&
    optStr(b.summary_text) &&
    opt(isConversation)(b.conversation) &&
    opt(listOf(LIST_LIMITS.messages, isConversationMessage))(b.messages) &&
    opt(listOf(LIST_LIMITS.actions, isActionSpec))(b.actions) &&
    opt(isProgress)(b.progress) &&
    opt(isLive)(b.live) &&
    optMs(b.when) &&
    optBool(b.show_when) &&
    opt(isChronometer)(b.chronometer) &&
    opt(isCall)(b.call) &&
    opt(isGroup)(b.group) &&
    opt(isChannel)(b.channel) &&
    optBool(b.resync),
  'notification.removed@1': (b) => isStr(b.key),
  'notification.dismiss@1': (b) => isStr(b.key),
  'clipboard.set@1': (b) =>
    b.mime === 'text/plain' && isStr(b.text) && typeof b.sensitive === 'boolean' && optStr(b.source),
  'device.status@1': (b) =>
    (b.battery === undefined ||
      (Number.isInteger(b.battery) && (b.battery as number) >= 0 && (b.battery as number) <= 100)) &&
    optBool(b.charging),
  'notification.action@1': (b) => isStr(b.key) && isActionRef(b.action),
  'notification.action_result@1': (b) => isBool(b.ok) && optStr(b.error),
  'media.state@1': (b) =>
    isNonEmptyStr(b.session) &&
    isApp(b.app) &&
    optStr(b.title) &&
    optStr(b.artist) &&
    optStr(b.album) &&
    optBlob(b.art) &&
    isStr(b.state) &&
    optMs(b.position_ms) &&
    optMs(b.position_at) &&
    optMs(b.duration_ms) &&
    isStrList(b.controls),
  'media.command@1': (b) =>
    isNonEmptyStr(b.session) &&
    isStr(b.command) &&
    COMMANDS.has(b.command) &&
    optMs(b.position_ms) &&
    (b.command !== 'seek' || b.position_ms !== undefined),
  'notification.resync@1': () => true,
};

/** Checks the known fields of a body. */
export function isValidBody(type: MessageType, body: unknown): boolean {
  return isObj(body) && BODY_VALIDATORS[type](body);
}
