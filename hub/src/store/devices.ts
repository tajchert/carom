// Device rows: identity keys, token hash, pending/active state, ack cursor, record.

export type DeviceStatus = "pending" | "active";

export type DeviceRow = {
  id: string;
  status: DeviceStatus;
  sign_pub: string;
  kx_pub: string;
  token_hash: string;
  created_at: number;
  last_seen: number;
  cursor: number;
  record: string | null;
};

export const LAST_SEEN_INTERVAL_MS = 10 * 60 * 1000;

export function getDevice(sql: SqlStorage, id: string): DeviceRow | null {
  return sql.exec<DeviceRow>("SELECT * FROM devices WHERE id = ?", id).toArray()[0] ?? null;
}

export interface NewDevice {
  id: string;
  status: DeviceStatus;
  signPub: string;
  kxPub: string;
  tokenHash: string;
  now: number;
}

export function insertDevice(sql: SqlStorage, d: NewDevice): void {
  sql.exec(
    "INSERT INTO devices (id, status, sign_pub, kx_pub, token_hash, created_at, last_seen) VALUES (?, ?, ?, ?, ?, ?, ?)",
    d.id,
    d.status,
    d.signPub,
    d.kxPub,
    d.tokenHash,
    d.now,
    d.now,
  );
}

export function activateDevice(sql: SqlStorage, id: string): void {
  sql.exec("UPDATE devices SET status = 'active' WHERE id = ?", id);
}

export function deleteDevice(sql: SqlStorage, id: string): void {
  sql.exec("DELETE FROM devices WHERE id = ?", id);
}

export function deletePendingDevice(sql: SqlStorage, id: string): void {
  sql.exec("DELETE FROM devices WHERE id = ? AND status = 'pending'", id);
}

export function listActiveDevices(sql: SqlStorage): DeviceRow[] {
  return sql.exec<DeviceRow>("SELECT * FROM devices WHERE status = 'active' ORDER BY created_at, id").toArray();
}

export function activeDeviceIds(sql: SqlStorage): Set<string> {
  return new Set(sql.exec<{ id: string }>("SELECT id FROM devices WHERE status = 'active'").toArray().map((r) => r.id));
}

/** Writes last_seen at most once per LAST_SEEN_INTERVAL_MS to save row writes. */
export function touchLastSeen(sql: SqlStorage, device: DeviceRow, now: number): void {
  if (now - device.last_seen < LAST_SEEN_INTERVAL_MS) return;
  sql.exec("UPDATE devices SET last_seen = ? WHERE id = ?", now, device.id);
}

export function setRecord(sql: SqlStorage, id: string, record: string): void {
  sql.exec("UPDATE devices SET record = ? WHERE id = ?", record, id);
}

export function setCursor(sql: SqlStorage, id: string, cursor: number): void {
  sql.exec("UPDATE devices SET cursor = ? WHERE id = ?", cursor, id);
}
