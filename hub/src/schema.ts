// SQLite schema with a forward-only migration runner keyed by meta.schema_version.

const MIGRATIONS: readonly string[] = [
  // v1
  `
  CREATE TABLE devices (
    id            TEXT PRIMARY KEY,
    status        TEXT NOT NULL CHECK (status IN ('pending', 'active')),
    sign_pub      TEXT NOT NULL,
    kx_pub        TEXT NOT NULL,
    token_hash    TEXT NOT NULL,
    created_at    INTEGER NOT NULL,
    last_seen     INTEGER NOT NULL,
    cursor        INTEGER NOT NULL DEFAULT 0,  -- cumulative ack: every seq <= cursor is acknowledged
    record        TEXT,                        -- sealed device record (JSON), opaque to the Hub
    push_endpoint TEXT,
    push_p256dh   TEXT,
    push_auth     TEXT,
    push_sent_at  INTEGER                      -- last wake-up push since the device last fetched
  ) WITHOUT ROWID;

  -- seq comes from meta.last_seq (see insertMessage), never from AUTOINCREMENT or MAX(seq)+1.
  CREATE TABLE messages (
    seq        INTEGER PRIMARY KEY,
    sender     TEXT NOT NULL,
    msg_id     TEXT NOT NULL,
    collapse   TEXT,
    recipients TEXT NOT NULL,                  -- JSON array of device ids
    envelope   TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    UNIQUE (sender, msg_id)
  );
  CREATE UNIQUE INDEX messages_collapse ON messages (sender, collapse) WHERE collapse IS NOT NULL;

  CREATE TABLE pairings (
    pair_id    TEXT PRIMARY KEY,
    offerer    TEXT NOT NULL,
    state      TEXT NOT NULL CHECK (state IN ('waiting', 'accepted', 'done')),
    expires_at INTEGER NOT NULL,
    candidate  TEXT,
    mac        TEXT,
    pop        TEXT,
    box        TEXT
  ) WITHOUT ROWID;

  CREATE TABLE keyring (
    kv     INTEGER PRIMARY KEY,
    by_device TEXT NOT NULL,
    sig       TEXT NOT NULL,
    boxes     TEXT NOT NULL                      -- JSON {device_id: {epk, ct}}
  );

  CREATE TABLE lockouts (
    route        TEXT PRIMARY KEY,
    window_start INTEGER NOT NULL,
    failures     INTEGER NOT NULL,
    locked_until INTEGER NOT NULL
  ) WITHOUT ROWID;
  `,
  // v2: a fully acknowledged message stays as a tombstone (envelope NULL) until expires_at, so
  // duplicate and superseded answers outlive delivery (SPEC §6 rules 5 and 7). The indexes keep
  // expiry, pending-count and backlog queries from scanning tombstones.
  `
  CREATE TABLE messages_v2 (
    seq        INTEGER PRIMARY KEY,
    sender     TEXT NOT NULL,
    msg_id     TEXT NOT NULL,
    collapse   TEXT,
    recipients TEXT NOT NULL,                  -- JSON array of device ids; '[]' for a tombstone
    envelope   TEXT,                           -- NULL once every recipient acknowledged it
    expires_at INTEGER NOT NULL,
    UNIQUE (sender, msg_id)
  );
  INSERT INTO messages_v2 SELECT seq, sender, msg_id, collapse, recipients, envelope, expires_at FROM messages;
  DROP TABLE messages;
  ALTER TABLE messages_v2 RENAME TO messages;
  CREATE UNIQUE INDEX messages_collapse ON messages (sender, collapse) WHERE collapse IS NOT NULL;
  CREATE INDEX messages_expires ON messages (expires_at);
  CREATE INDEX messages_live ON messages (seq) WHERE envelope IS NOT NULL;
  CREATE INDEX pairings_expires ON pairings (expires_at);
  `,
];

/** Tables wiped by POST /v1/reset (everything except meta.schema_version). */
export const DATA_TABLES = ["devices", "messages", "pairings", "keyring", "lockouts"] as const;

export function migrate(storage: DurableObjectStorage): void {
  const sql = storage.sql;
  sql.exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID");
  const row = sql.exec<{ value: string }>("SELECT value FROM meta WHERE key = 'schema_version'").toArray()[0];
  const current = row ? Number(row.value) : 0;
  if (current >= MIGRATIONS.length) return;
  storage.transactionSync(() => {
    for (let v = current; v < MIGRATIONS.length; v++) sql.exec(MIGRATIONS[v]!);
    sql.exec("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', ?)", String(MIGRATIONS.length));
  });
}
