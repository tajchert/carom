// Group-key versions (SPEC §5.5). Never pruned by TTL; every version is kept (they are tiny).

export type KeyringRow = { kv: number; by_device: string; sig: string; boxes: string };

export function latestKv(sql: SqlStorage): number | null {
  return sql.exec<{ kv: number | null }>("SELECT MAX(kv) AS kv FROM keyring").one().kv;
}

export function insertKeyring(sql: SqlStorage, kv: number, by: string, sig: string, boxes: string): void {
  sql.exec("INSERT INTO keyring (kv, by_device, sig, boxes) VALUES (?, ?, ?, ?)", kv, by, sig, boxes);
}

export function keyringAfter(sql: SqlStorage, have: number): KeyringRow[] {
  return sql.exec<KeyringRow>("SELECT * FROM keyring WHERE kv > ? ORDER BY kv", have).toArray();
}
