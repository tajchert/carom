// Queued envelopes. One row per message; recipients are a JSON array and delivery state is
// each device's cumulative ack cursor, so a send costs the same row writes regardless of fan-out.
// Once every recipient has acknowledged a message, its row becomes a tombstone (envelope NULL)
// that only answers duplicate and superseded checks until expires_at.

import { getMeta, setMeta } from "./meta";

export type StoredMessage = { seq: number; envelope: string };

export interface NewMessage {
  sender: string;
  msgId: string;
  collapse: string | null;
  recipients: readonly string[];
  envelope: string;
  expiresAt: number;
}

/**
 * The highest seq ever issued. Seqs come from a counter in meta rather than MAX(seq)+1: a
 * message replaced by collapse must never have its seq reused, or a device acking the old
 * message would silently acknowledge the new one.
 */
export function highestSeq(sql: SqlStorage): number {
  return Number(getMeta(sql, "last_seq") ?? 0);
}

export function insertMessage(sql: SqlStorage, m: NewMessage): number {
  const seq = highestSeq(sql) + 1;
  setMeta(sql, "last_seq", String(seq));
  sql.exec(
    "INSERT INTO messages (seq, sender, msg_id, collapse, recipients, envelope, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
    seq,
    m.sender,
    m.msgId,
    m.collapse,
    JSON.stringify(m.recipients),
    m.envelope,
    m.expiresAt,
  );
  return seq;
}

/** Also finds tombstones, so a retry after delivery is still a duplicate. */
export function findByMsgId(sql: SqlStorage, sender: string, msgId: string): number | null {
  const row = sql.exec<{ seq: number }>("SELECT seq FROM messages WHERE sender = ? AND msg_id = ?", sender, msgId).toArray()[0];
  return row?.seq ?? null;
}

/** Also finds a tombstone, so a late retry of an older msg_id is still superseded. */
export function findByCollapse(sql: SqlStorage, sender: string, collapse: string): { seq: number; msg_id: string } | null {
  return (
    sql
      .exec<{ seq: number; msg_id: string }>("SELECT seq, msg_id FROM messages WHERE sender = ? AND collapse = ?", sender, collapse)
      .toArray()[0] ?? null
  );
}

export function deleteMessage(sql: SqlStorage, seq: number): void {
  sql.exec("DELETE FROM messages WHERE seq = ?", seq);
}

/**
 * Unexpired, unacknowledged messages addressed to `deviceId` with seq above `after`, in seq
 * order. Returns the cursor, so a caller can stop early without reading the remaining rows.
 */
export function unackedFor(sql: SqlStorage, deviceId: string, after: number, now: number, limit: number): SqlStorageCursor<StoredMessage> {
  return sql.exec<StoredMessage>(
    `SELECT seq, envelope FROM messages INDEXED BY messages_live
     WHERE seq > ? AND envelope IS NOT NULL AND expires_at > ?
       AND EXISTS (SELECT 1 FROM json_each(messages.recipients) WHERE value = ?)
     ORDER BY seq LIMIT ?`,
    after,
    now,
    deviceId,
    limit,
  );
}

/**
 * How many messages `deviceId` has pending, counting no further than `cap`. `exceptSeq` is left
 * out (a message about to be replaced by collapse).
 */
export function pendingCount(sql: SqlStorage, deviceId: string, now: number, cap: number, exceptSeq: number): number {
  return sql
    .exec<{ n: number }>(
      `SELECT COUNT(*) AS n FROM (
         SELECT 1 FROM messages INDEXED BY messages_live
         WHERE seq > (SELECT cursor FROM devices WHERE id = ?) AND envelope IS NOT NULL AND expires_at > ? AND seq != ?
           AND EXISTS (SELECT 1 FROM json_each(messages.recipients) WHERE value = ?)
         LIMIT ?)`,
      deviceId,
      now,
      exceptSeq,
      deviceId,
      cap,
    )
    .one().n;
}

/**
 * Turns messages with seq in (fromExclusive, toInclusive] that every still-existing recipient has
 * acknowledged into tombstones. Moving one cursor from `from` to `to` can only complete messages
 * in that range, so an ack never looks at the rest of the queue.
 */
export function tombstoneDelivered(sql: SqlStorage, fromExclusive: number, toInclusive = Number.MAX_SAFE_INTEGER): void {
  sql.exec(
    `UPDATE messages INDEXED BY messages_live SET envelope = NULL, recipients = '[]'
     WHERE seq > ? AND seq <= ? AND envelope IS NOT NULL AND NOT EXISTS (
       SELECT 1 FROM json_each(messages.recipients) r JOIN devices d ON d.id = r.value
       WHERE d.cursor < messages.seq)`,
    fromExclusive,
    toInclusive,
  );
}

/** Deletes expired messages and tombstones (an index range over the expired rows only). */
export function purgeExpired(sql: SqlStorage, now: number): void {
  sql.exec("DELETE FROM messages WHERE expires_at <= ?", now);
}

/**
 * Removes a revoked device from its pending deliveries, then tombstones messages nobody else
 * awaits. Only messages above its cursor were still pending for it.
 */
export function dropRecipient(sql: SqlStorage, deviceId: string, cursor: number): void {
  sql.exec(
    `UPDATE messages INDEXED BY messages_live
     SET recipients = (SELECT json_group_array(value) FROM json_each(messages.recipients) WHERE value != ?)
     WHERE seq > ? AND envelope IS NOT NULL AND EXISTS (SELECT 1 FROM json_each(messages.recipients) WHERE value = ?)`,
    deviceId,
    cursor,
    deviceId,
  );
  tombstoneDelivered(sql, cursor);
}

export function earliestMessageExpiry(sql: SqlStorage): number | null {
  return sql.exec<{ t: number | null }>("SELECT MIN(expires_at) AS t FROM messages").one().t;
}
