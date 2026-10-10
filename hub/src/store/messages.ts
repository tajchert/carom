// Queued envelopes. One row per message; recipients are a JSON array and delivery state is
// each device's cumulative ack cursor, so a send costs the same row writes regardless of fan-out.

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

export function findByMsgId(sql: SqlStorage, sender: string, msgId: string): number | null {
  const row = sql.exec<{ seq: number }>("SELECT seq FROM messages WHERE sender = ? AND msg_id = ?", sender, msgId).toArray()[0];
  return row?.seq ?? null;
}

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

/** Unexpired messages addressed to `deviceId` above its cursor, in seq order. */
export function unackedFor(sql: SqlStorage, deviceId: string, cursor: number, now: number, limit: number): StoredMessage[] {
  return sql
    .exec<StoredMessage>(
      `SELECT seq, envelope FROM messages
       WHERE seq > ? AND expires_at > ?
         AND EXISTS (SELECT 1 FROM json_each(messages.recipients) WHERE value = ?)
       ORDER BY seq LIMIT ?`,
      cursor,
      now,
      deviceId,
      limit,
    )
    .toArray();
}

/** Deletes messages that every still-existing recipient has acknowledged. */
export function purgeDelivered(sql: SqlStorage): void {
  sql.exec(
    `DELETE FROM messages WHERE NOT EXISTS (
       SELECT 1 FROM json_each(messages.recipients) r JOIN devices d ON d.id = r.value
       WHERE d.cursor < messages.seq)`,
  );
}

export function purgeExpired(sql: SqlStorage, now: number): void {
  sql.exec("DELETE FROM messages WHERE expires_at <= ?", now);
}

/** Removes `deviceId` from every pending delivery, then drops messages nobody else awaits. */
export function dropRecipient(sql: SqlStorage, deviceId: string): void {
  sql.exec(
    `UPDATE messages
     SET recipients = (SELECT json_group_array(value) FROM json_each(messages.recipients) WHERE value != ?)
     WHERE EXISTS (SELECT 1 FROM json_each(messages.recipients) WHERE value = ?)`,
    deviceId,
    deviceId,
  );
  purgeDelivered(sql);
}

export function earliestMessageExpiry(sql: SqlStorage): number | null {
  return sql.exec<{ t: number | null }>("SELECT MIN(expires_at) AS t FROM messages").one().t;
}
