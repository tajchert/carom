// Encrypted blobs (SPEC §11): ciphertext keyed by b64(sha256(ct)). meta.blob_bytes holds the
// stored total, so the quota check on upload never sums the table. Every insert and delete goes
// through this module, which keeps that total in step.

import { getMeta, setMeta } from "./meta";

export type BlobRow = { size: number; msg_id: string | null; expires_at: number };

export interface NewBlob {
  id: string;
  owner: string;
  msgId: string | null;
  data: ArrayBuffer;
  expiresAt: number;
}

export function blobBytes(sql: SqlStorage): number {
  return Number(getMeta(sql, "blob_bytes") ?? 0);
}

function addBlobBytes(sql: SqlStorage, delta: number): void {
  setMeta(sql, "blob_bytes", String(Math.max(0, blobBytes(sql) + delta)));
}

/** Also returns an expired row the alarm has not deleted yet. */
export function getBlob(sql: SqlStorage, id: string): BlobRow | null {
  return sql.exec<BlobRow>("SELECT size, msg_id, expires_at FROM blobs WHERE id = ?", id).toArray()[0] ?? null;
}

export function getBlobData(sql: SqlStorage, id: string, now: number): { data: ArrayBuffer; expires_at: number } | null {
  return (
    sql
      .exec<{ data: ArrayBuffer; expires_at: number }>("SELECT data, expires_at FROM blobs WHERE id = ? AND expires_at > ?", id, now)
      .toArray()[0] ?? null
  );
}

export function insertBlob(sql: SqlStorage, b: NewBlob): void {
  sql.exec(
    "INSERT INTO blobs (id, owner, msg_id, size, data, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
    b.id,
    b.owner,
    b.msgId,
    b.data.byteLength,
    b.data,
    b.expiresAt,
  );
  addBlobBytes(sql, b.data.byteLength);
}

/** Sets expires_at. `detach` clears msg_id, so superseding that message no longer deletes the blob. */
export function extendBlob(sql: SqlStorage, id: string, expiresAt: number, detach: boolean): void {
  if (detach) sql.exec("UPDATE blobs SET expires_at = ?, msg_id = NULL WHERE id = ?", expiresAt, id);
  else sql.exec("UPDATE blobs SET expires_at = ? WHERE id = ?", expiresAt, id);
}
