import { runInDurableObject } from "cloudflare:test";
import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import type { Hub } from "../src/hub";
import { migrate } from "../src/schema";

describe("schema migrations", () => {
  it("v2 keeps queued v1 messages and adds the tombstone and expiry indexes", async () => {
    const stub = env.HUB.get(env.HUB.idFromName("migration-test"));
    const result = await runInDurableObject(stub, (_hub: Hub, state) => {
      const sql = state.storage.sql;
      // Put the database back into its v1 shape, with one queued message.
      sql.exec("DROP TABLE messages");
      sql.exec("DROP INDEX pairings_expires");
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
      version: "2",
      rows: [{ seq: 7, sender: "dev_a", msg_id: "m1", collapse: "c1", recipients: '["dev_b"]', envelope: '{"x":1}', expires_at: 123 }],
      indexes: ["messages_collapse", "messages_expires", "messages_live", "pairings_expires"],
      nullable: 0,
    });
  });
});
