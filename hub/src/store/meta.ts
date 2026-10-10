// Small key/value settings: schema_version, claimed, vapid.

export function getMeta(sql: SqlStorage, key: string): string | null {
  return sql.exec<{ value: string }>("SELECT value FROM meta WHERE key = ?", key).toArray()[0]?.value ?? null;
}

export function setMeta(sql: SqlStorage, key: string, value: string): void {
  sql.exec("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", key, value);
}

/** Inserts only when absent; returns the value that is stored afterwards. */
export function setMetaOnce(sql: SqlStorage, key: string, value: string): string {
  sql.exec("INSERT OR IGNORE INTO meta (key, value) VALUES (?, ?)", key, value);
  return getMeta(sql, key)!;
}

export function deleteMetaExceptSchema(sql: SqlStorage): void {
  sql.exec("DELETE FROM meta WHERE key != 'schema_version'");
}

export function isClaimed(sql: SqlStorage): boolean {
  return getMeta(sql, "claimed") === "1";
}
