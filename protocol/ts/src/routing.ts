// Default receive rules (SPEC §5.1) and sender-side routing (SPEC §5.2).

import { sortIds } from './device.ts';
import type { MessageType } from './messages.ts';
import type { DeviceKind, DeviceRecord, ReceiveRule } from './record.ts';

/**
 * `receive` written at pairing. SPEC §5.1 defines phone and desktop; a CLI gets the
 * clipboard only (the spec leaves `cli` open).
 */
export function defaultReceive(kind: DeviceKind): Partial<Record<MessageType, ReceiveRule>> {
  switch (kind) {
    case 'phone':
      return { 'clipboard.set@1': '*', 'notification.dismiss@1': '*' };
    case 'desktop':
      return {
        'notification.posted@1': '*',
        'notification.removed@1': '*',
        'device.status@1': '*',
        'clipboard.set@1': '*',
      };
    case 'cli':
      return { 'clipboard.set@1': '*' };
  }
}

export interface RouteInput {
  type: string;
  senderId: string;
  /** Trusted directory records (SPEC §5.3). */
  directory: readonly DeviceRecord[];
  /** Ids known to be revoked or inactive. */
  revoked?: ReadonlySet<string>;
  /** For replies (payload `reply_to`): the original message's sender. */
  replyToSender?: string;
}

/** Recipients for a message of `type`, sorted ascending. Never includes the sender. */
export function routeRecipients(input: RouteInput): string[] {
  if (input.replyToSender !== undefined) {
    return input.replyToSender === input.senderId ? [] : [input.replyToSender];
  }
  const to = input.directory.filter((d) => {
    if (d.id === input.senderId || input.revoked?.has(d.id) || !d.caps.includes(input.type)) return false;
    const rule = Object.hasOwn(d.receive, input.type) ? d.receive[input.type] : undefined;
    return rule === '*' || (Array.isArray(rule) && rule.includes(input.senderId));
  });
  return sortIds(new Set(to.map((d) => d.id)));
}
