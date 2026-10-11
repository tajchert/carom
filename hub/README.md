# carom-hub

The Carom Hub: a Cloudflare Worker plus one SQLite-backed Durable Object that relays
end-to-end encrypted envelopes between your devices. It implements protocol v1 from
[`protocol/SPEC.md`](../protocol/SPEC.md) §6–§10. The Hub only ever sees ciphertext: it
routes, queues and enforces the rules, but it never holds the group key. It sends no push
notifications of any kind; devices fetch when they are already awake (SPEC §9).

## Architecture

```
client ──HTTPS/WS──▶ Worker (src/index.ts) ──stub.fetch──▶ Hub Durable Object (src/hub.ts)
                     • route match + body limits          • SQLite: devices, messages,
                     • token MAC check (no DO contact       pairings, keyring, lockouts, blobs, meta
                       for garbage tokens)                • WebSocket hibernation
                     • strips client x-carom-* headers,   • one alarm for all expiries
                       sets x-carom-device/-token-hash
```

- **One Hub per deployment**, reached via `idFromName("hub")`. With `HUB_JURISDICTION=eu`
  the Worker uses `env.HUB.jurisdiction("eu")`, so the object and its data stay in the EU.
  The value is fixed for the life of a deployment: changing it points at a different, empty
  Hub.
- **Tokens** are `ct1.<device_id>.<rand32>.<mac16>`, MAC-keyed by a key derived from the
  `SETUP_TOKEN` secret. The Worker verifies the MAC; the Hub then checks `SHA-256(token)`
  against the device row (revocation and pending state). A new `SETUP_TOKEN` invalidates
  every token.
- **Storage is tuned for the Free plan's 100k rows written/day.** A message is one row
  (recipients are a JSON array) plus the `last_seq` counter; delivery state is one
  cumulative ack cursor per device, so acking any number of messages is one write.
  Messages are deleted once every recipient's cursor has passed them, or at `ttl_s`.
  `last_seen` is written at most every 10 minutes. Seqs never repeat, even when collapse
  deletes the newest message, so an ack can never cover a message the device has not seen.
- **Hibernation.** Sockets are tagged with their device id and nothing is kept in memory.
  The text frame `ping` is answered with `pong` by the runtime without waking the Hub.
- **No push.** A message reaches a recipient only over its live WebSocket or when it fetches
  (`GET /v1/messages`); an offline recipient's messages wait in the queue until `ttl_s`.
  Schema v3 dropped the old push subscription columns and the stored VAPID key.
- **Blobs.** Encrypted images (SPEC §11) are rows in the `blobs` table, keyed by
  `b64(SHA-256(ct))`. The Hub checks the hash, the 1 MiB + 1 KiB body limit and a
  256 MiB total quota. The total lives in `meta.blob_bytes`, so an upload never sums the
  table. A blob expires at its own `ttl_s` (60 s to 7 days), when the message it was
  uploaded with is superseded, or when its uploader is revoked.

### Source map

| File | Purpose |
|---|---|
| `src/index.ts` | Worker entry: routing, body limits, token MAC, forwarding |
| `src/hub.ts` | Durable Object: auth, dispatch, alarm, WebSocket handlers, reset |
| `src/routes.ts` | Route table shared by Worker and Hub (auth kind, body limit) |
| `src/handlers/*.ts` | One module per area: admin, pairing, devices, messages, keyring |
| `src/store/*.ts` | SQL for each table |
| `src/schema.ts` | Schema and `schema_version` migration runner |
| `src/envelope.ts`, `src/record.ts`, `src/seal.ts` | Shape checks and Ed25519 signature inputs (SPEC §2.3, §3, §5.1) |
| `src/token.ts` | Token minting and MAC verification, setup-token comparison |
| `src/sockets.ts` | WebSocket frames |

## Routes

All of SPEC §6. Errors are `{"error": {"code", "message"}}`.

| Route | Auth |
|---|---|
| `GET /v1/info` | none |
| `POST /v1/setup`, `POST /v1/reset` | setup token in body; 10 failures/hour lock the route for an hour |
| `POST /v1/pair/accept` | none (creates a *pending* device) |
| `GET /v1/pair/:pair_id/welcome` | the candidate's token (pending or just activated) |
| everything else | an active device token |

Body limits: 100 KiB for `POST /v1/messages` (one 98 304-byte envelope plus wrapper),
1 MiB + 1 KiB for `PUT /v1/blobs/:id`, 64 KiB for records, keyring and welcome boxes, and
4 KiB elsewhere. Oversized bodies get `413 too_large` before the Hub is contacted.

Beyond SPEC §6 the Hub uses two extra error codes: `503 unavailable` when the deployment is
misconfigured (missing or short `SETUP_TOKEN`, bad `HUB_JURISDICTION`) and `500 internal`.

## Local development

```sh
cp .dev.vars.example .dev.vars   # then put a 32+ character SETUP_TOKEN in it
npm run dev                      # wrangler dev, http://localhost:8787
curl localhost:8787/v1/info
```

## Tests and checks

```sh
npm test            # vitest + @cloudflare/vitest-plugin, runs inside workerd
npm run typecheck   # src and test
npm run types       # regenerate src/worker-configuration.d.ts after editing wrangler.jsonc
```

Tests drive the real Worker and Durable Object. Test clients seal envelopes and records with
`@noble/*`, independently of `src/`, so signature checks are verified against a second
implementation.

## Smoke test

`scripts/smoke.mjs` is an end-to-end check of a deployed Hub. It drives two simulated devices
(a Mac and a phone) with real crypto from `@carom/protocol`: setup, pairing over the
`carom://pair` URI and the WebSocket `pair` frame, live WebSocket delivery with latency,
collapse in the backlog, duplicate and superseded posts, fetch and ack over HTTP,
revocation, rekey through the keyring, and negative auth/signature checks. It
prints one PASS/FAIL line per step with timings and exits non-zero on any failure.

```sh
node hub/scripts/smoke.mjs --url https://carom-hub-<name>.<account>.workers.dev [--setup-token-file <path>] [--reset]
```

The setup token comes from `--setup-token-file` (JSON with a `setupToken` field, or the raw
token), defaulting to `~/.config/carom-hub/<worker name>.json`, or from `CAROM_SETUP_TOKEN`.
The Hub must be unclaimed; `--reset` wipes a claimed Hub first. **The test always finishes
with `POST /v1/reset`, so only point it at a dev Hub.** Tokens are never printed.

## Deploying

The `carom-hub` CLI (separate) runs, from this folder:

```sh
wrangler deploy --secrets-file <file with SETUP_TOKEN> --var HUB_JURISDICTION:<"" or eu>
```

There is no build step beyond Wrangler's bundler, and `wrangler` is a runtime dependency of
this package. The Deploy to Cloudflare button reads `.dev.vars.example` to ask for
`SETUP_TOKEN`. Never rename the `Hub` class or edit the `v1` migration.

## Limits and cost

Everything fits the Workers Free plan: one Worker, one SQLite Durable Object, no KV, D1,
Queues or paid features. Rough cost per message: one request to the Worker, one to the Hub,
a handful of row writes for the send (the message row, its two indexes and the seq counter)
and one for each recipient's ack (shared across all messages it acks at once).
The Hub never makes outbound requests.
Hibernated WebSockets cost nothing while idle; protocol pings and the `ping` text frame are
answered without waking the Hub.

Envelopes: plaintext up to 65 536 bytes, `ttl_s` 60–604 800, 1–32 recipients. At most 5
open pairing offers, each valid for 5 minutes.

Blobs: at most 1 049 600 bytes each and 256 MiB in total. An upload is one row write plus
the byte counter.
