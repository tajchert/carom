// v1 message types and their bodies (SPEC §4). Receivers ignore unknown fields,
// so validators only check the fields they know.

export const MESSAGE_TYPES = [
  'notification.posted@1',
  'notification.removed@1',
  'notification.dismiss@1',
  'clipboard.set@1',
  'device.status@1',
] as const;

export type MessageType = (typeof MESSAGE_TYPES)[number];

export interface NotificationPostedBody {
  key: string;
  app: { id: string; name: string };
  title?: string;
  text?: string;
  sub_text?: string;
  category?: string;
  posted_at: number;
  redacted: boolean;
  ongoing?: boolean;
}

export interface NotificationRemovedBody {
  key: string;
}

/** Sent with payload-level `reply_to` = the posted message's msg_id. */
export interface NotificationDismissBody {
  key: string;
}

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

export interface MessageBodies {
  'notification.posted@1': NotificationPostedBody;
  'notification.removed@1': NotificationRemovedBody;
  'notification.dismiss@1': NotificationDismissBody;
  'clipboard.set@1': ClipboardSetBody;
  'device.status@1': DeviceStatusBody;
}

const DAY_S = 86_400;

export const DEFAULT_TTL_S: Record<MessageType, number> = {
  'notification.posted@1': 2 * DAY_S,
  'notification.removed@1': 2 * DAY_S,
  'notification.dismiss@1': DAY_S,
  'clipboard.set@1': DAY_S,
  'device.status@1': DAY_S,
};

export function isMessageType(t: unknown): t is MessageType {
  return typeof t === 'string' && (MESSAGE_TYPES as readonly string[]).includes(t);
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
    default:
      return null;
  }
}

type Obj = Record<string, unknown>;

function isObj(v: unknown): v is Obj {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}
const isStr = (v: unknown): v is string => typeof v === 'string';
const optStr = (v: unknown): boolean => v === undefined || isStr(v);
const optBool = (v: unknown): boolean => v === undefined || typeof v === 'boolean';
const isMs = (v: unknown): boolean => Number.isSafeInteger(v) && (v as number) >= 0;

const BODY_VALIDATORS: Record<MessageType, (b: Obj) => boolean> = {
  'notification.posted@1': (b) =>
    isStr(b.key) &&
    isObj(b.app) &&
    isStr(b.app.id) &&
    isStr(b.app.name) &&
    optStr(b.title) &&
    optStr(b.text) &&
    optStr(b.sub_text) &&
    optStr(b.category) &&
    isMs(b.posted_at) &&
    typeof b.redacted === 'boolean' &&
    optBool(b.ongoing),
  'notification.removed@1': (b) => isStr(b.key),
  'notification.dismiss@1': (b) => isStr(b.key),
  'clipboard.set@1': (b) =>
    b.mime === 'text/plain' && isStr(b.text) && typeof b.sensitive === 'boolean' && optStr(b.source),
  'device.status@1': (b) =>
    (b.battery === undefined ||
      (Number.isInteger(b.battery) && (b.battery as number) >= 0 && (b.battery as number) <= 100)) &&
    optBool(b.charging),
};

/** Checks the known fields of a v1 body. */
export function isValidBody(type: MessageType, body: unknown): boolean {
  return isObj(body) && BODY_VALIDATORS[type](body);
}
