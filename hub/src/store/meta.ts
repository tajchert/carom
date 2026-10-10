// Small key/value settings: schema_version, claimed.

export function getMeta(sql: SqlStorage, key: string): string | null {
  return sql.exec<{ value: string }>("SELECT value FROM meta WHERE key = ?", key).toArray()[0]?.value ?? null;
}

export function setMeta(sql: SqlStorage, key: string, value: string): void {
  sql.exec("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", key, value);
}

export function deleteMetaExceptSchema(sql: SqlStorage): void {
  sql.exec("DELETE FROM meta WHERE key != 'schema_version'");
}

export function isClaimed(sql: SqlStorage): boolean {
  return getMeta(sql, "claimed") === "1";
}
