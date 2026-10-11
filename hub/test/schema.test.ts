import { runInDurableObject } from "cloudflare:test";
import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import type { Hub } from "../src/hub";
import { DATA_TABLES, migrate } from "../src/schema";

describe("schema migrations", () => {
  it("v2 keeps queued v1 messages and adds the tombstone and expiry indexes", async () => {
    const stub = env.HUB.get(env.HUB.idFromName("migration-test"));
    const result = await runInDurableObject(stub, (_hub: Hub, state) => {
      const sql = state.storage.sql;
      // Put the database back into its v1 shape, with one queued message.
      sql.exec("DROP TABLE messages");
      sql.exec("DROP TABLE blobs");
      sql.exec("DROP INDEX pairings_expires");
      for (const column of ["push_endpoint", "push_p256dh", "push_auth"]) sql.exec(`ALTER TABLE devices ADD COLUMN ${column} TEXT`);
      sql.exec("ALTER TABLE devices ADD COLUMN push_sent_at INTEGER");
      sql.exec(`CREATE TABLE messages (
        seq INTEGER PRIMARY KEY, sender TEXT NOT NULL, msg_id TEXT NOT NULL, collapse TEXT,
        recipients TEXT NOT NULL, envelope TEXT NOT NULL, expires_at INTEGER NOT NULL, UNIQUE (sender, msg_id))`);
      sql.exec("CREATE UNIQUE INDEX messages_collapse ON messages (sender, collapse) WHERE collapse IS NOT NULL");
      sql.exec(`INSERT INTO messages VALUES (7, 'dev_a', 'm1', 'c1', '["dev_b"]', '{"x":1}', 123)`);
      sql.exec("UPDATE meta SET value = '1' WHERE key = 'schema_version'");

      migrate(state.storage);
      return {
        version: sql.exec("SELECT value FROM meta WHERE key = 'schema_version'").one().value,
        rows: sql.exec("SELECT * FROM messages").toArray(),
        indexes: sql.exec("SELECT name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%' ORDER BY name").toArray().map((r) => r.name),
        nullable: sql.exec("SELECT \"notnull\" AS nn FROM pragma_table_info('messages') WHERE name = 'envelope'").one().nn,
      };
    });
    expect(result).toEqual({
      version: "4",
      rows: [{ seq: 7, sender: "dev_a", msg_id: "m1", collapse: "c1", recipients: '["dev_b"]', envelope: '{"x":1}', expires_at: 123 }],
      indexes: ["blobs_expires", "blobs_msg", "messages_collapse", "messages_expires", "messages_live", "pairings_expires"],
      nullable: 0,
    });
  });

  it("v3 drops the push columns and the stored VAPID key, keeping devices", async () => {
    const stub = env.HUB.get(env.HUB.idFromName("migration-test-v3"));
    const result = await runInDurableObject(stub, (_hub: Hub, state) => {
      const sql = state.storage.sql;
      // Put the devices table back into its v2 shape, with a push subscription and a VAPID key.
      sql.exec("DROP TABLE devices");
      sql.exec("DROP TABLE blobs");
      sql.exec(`CREATE TABLE devices (
        id TEXT PRIMARY KEY, status TEXT NOT NULL CHECK (status IN ('pending', 'active')), sign_pub TEXT NOT NULL,
        kx_pub TEXT NOT NULL, token_hash TEXT NOT NULL, created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL,
        cursor INTEGER NOT NULL DEFAULT 0, record TEXT, push_endpoint TEXT, push_p256dh TEXT, push_auth TEXT,
        push_sent_at INTEGER) WITHOUT ROWID`);
      sql.exec(`INSERT INTO devices VALUES ('dev_a', 'active', 'sp', 'kp', 'th', 1, 2, 5, '{"r":1}', 'https://p.example/x', 'k', 'a', 9)`);
      sql.exec("INSERT OR REPLACE INTO meta (key, value) VALUES ('vapid', '{}'), ('claimed', '1')");
      sql.exec("UPDATE meta SET value = '2' WHERE key = 'schema_version'");

      migrate(state.storage);
      return {
        version: sql.exec("SELECT value FROM meta WHERE key = 'schema_version'").one().value,
        devices: sql.exec("SELECT * FROM devices").toArray(),
        meta: sql.exec("SELECT key FROM meta ORDER BY key").toArray().map((r) => r.key),
      };
    });
    expect(result).toEqual({
      version: "4",
      devices: [{ id: "dev_a", status: "active", sign_pub: "sp", kx_pub: "kp", token_hash: "th", created_at: 1, last_seen: 2, cursor: 5, record: '{"r":1}' }],
      meta: ["claimed", "schema_version"],
    });
  });

  it("v4 adds the blobs table with its expiry and message indexes, and reset covers it", async () => {
    const stub = env.HUB.get(env.HUB.idFromName("migration-test-v4"));
    const result = await runInDurableObject(stub, (_hub: Hub, state) => {
      const sql = state.storage.sql;
      sql.exec("DROP TABLE blobs");
      sql.exec("UPDATE meta SET value = '3' WHERE key = 'schema_version'");
      migrate(state.storage);
      return {
        version: sql.exec("SELECT value FROM meta WHERE key = 'schema_version'").one().value,
        columns: sql.exec("SELECT name FROM pragma_table_info('blobs') ORDER BY cid").toArray().map((r) => r.name),
        indexes: sql
          .exec("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'blobs' AND name NOT LIKE 'sqlite_%' ORDER BY name")
          .toArray()
          .map((r) => r.name),
      };
    });
    expect(result).toEqual({
      version: "4",
      columns: ["id", "owner", "msg_id", "size", "data", "expires_at"],
      indexes: ["blobs_expires", "blobs_msg"],
    });
    expect(DATA_TABLES).toContain("blobs");
  });
});
