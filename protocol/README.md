# Carom protocol

Everything the Hub and the clients must agree on.

| Path | What |
|---|---|
| [`SPEC.md`](SPEC.md) | The protocol v1 contract. |
| [`schemas/`](schemas/) | JSON Schemas (draft 2020-12) for the envelope, payload wrapper, each v1 message body, device records, the keyring body and the welcome plaintext. |
| [`vectors/`](vectors/) | Normative test vectors, generated from fixed seeds. Every implementation must pass them. |
| [`ts/`](ts/) | `@carom/protocol`, the TypeScript reference implementation (Node and Cloudflare Workers). |

## Running

From the repository root (npm workspaces) or from `protocol/ts`:

```sh
npm install
npm run typecheck -w @carom/protocol   # src (Workers lib only) + tests
npm test -w @carom/protocol            # unit tests, schema tests, vector checks
npm run build -w @carom/protocol       # emits protocol/ts/dist
npm run vectors -w @carom/protocol     # regenerates protocol/vectors/*.json
```

`npm test` fails if the committed vectors differ from what the generator produces, so a
change to any byte layout always shows up as a vector diff in review.

`ts/test/vectors-node-crypto.test.ts` re-derives every vector with `node:crypto` only,
without importing the reference implementation. It is the cross-implementation check:
keep it independent of `ts/src`.

## The TypeScript package

Dependencies are only `@noble/curves`, `@noble/hashes` and `@noble/ciphers`. `src/` uses no
Node APIs, so the package runs unchanged in Workers. Randomness is injectable everywhere
(`rng`, or explicit `salt` / `ephSk` / `rand` arguments).

Main entry points, all exported from `@carom/protocol`:

- Encodings: `b64encode`, `b64decode` (strict), `lp`, `u64`, `toHex`, `fromHex`.
- Identity: `generateIdentity`, `identityFromSecrets`, `deviceIdFromSignPub`, `isDeviceId`.
- Keys: `msgKey`, `collapseKey`, `pairKey`, `tokenKey`, `boxKey`, `collapseValue`.
- Envelopes: `encodePayload` (pads), `decodePayload`, `sealEnvelope`, `openEnvelope`,
  `envelopeAad`, `envelopeSigInput`, `verifyEnvelopeSignature`, `validateEnvelopeShape`
  (Hub-side structural checks, returns `bad_request` / `too_large`).
- Receiving: `receiveEnvelope` (SPEC §3.3 rules 1–7 in order) and its pure helpers
  `isAddressedTo`, `trustedSenderKey`, `checkFreshness`, `isStaleClipboard`, `isReplyAllowed`.
- Records: `sealRecord`, `openRecord` (SPEC §5.3 membership checks), `isValidDeviceRecord`.
- Box: `sealBox`, `openBox`.
- Pairing: `buildPairAccept`, `verifyPairCandidate`, `verifyPairPop`, `sealWelcome`, `openWelcome`.
- Rekey: `buildRekey`, `openRekey`, `verifyRekeySignature`, `rekeySigInput`, `rekeyBoxAad`.
- Tokens: `makeToken`, `parseToken`, `verifyToken`, `tokenHash`.
- QR: `buildSetupUri`, `buildPairUri`, `parseQrUri`.
- Ids and routing: `createUuidV7Generator`, `isUuidV7`, `routeRecipients`, `defaultReceive`.

Failures that come from untrusted input return `{ ok: false, error: '<code>' }` (or `null`
for simple parsers). Programmer errors, such as a wrong key length, throw `RangeError`.

## Consuming the vectors (Kotlin, Swift, others)

Each file in `vectors/` is JSON with a `description` that states the construction and the
error codes it uses. Conventions:

- `*_hex` fields are lowercase hex; plain JSON values (`envelope`, `box`, `sealed`, …) are
  exactly what goes over the wire, with b64 fields as base64url without padding.
- Fixture keys are `SHA-256("carom/v1/test-vectors/<label>")`. `device_id.json` lists every
  identity (`phone`, `mac`, `laptop`, `tablet`, `outsider`) with its secret and public keys.
  `sign_sk` is the 32-byte RFC 8032 seed (CryptoKit `rawRepresentation`); `kx_sk` is the raw
  X25519 scalar.
- Intermediate values (`header_fields_hex`, `aad_hex`, `msg_key_hex`, `sig_input_hex`,
  `shared_secret_hex`, `box_key_hex`, …) are there to pinpoint where a port diverges.
- Seal outputs (`ct_hex`, envelopes, boxes) are deterministic given the salt or ephemeral key
  in the vector, so a port can reproduce them byte for byte.
- Signatures marked `deterministic_rfc8032` come from a deterministic signer. A randomized
  signer such as CryptoKit produces different bytes. Check them by **verifying**, and check
  your own signatures by verifying them against the vector's public key.
- Negative cases give the input and the `expected` error code. A port may name its errors
  differently, but it must reject at the same rule, in the same order.
- `envelope_receive.json` cases carry a full receiver `context` (own id, trusted directory,
  keys by kv, clock, seen ids, caps, last clipboard ts, original recipients of sent
  messages) so each case is independent.
- `envelope_shape.json` is for the Hub. It needs no keys.
- In `uuidv7.json`, only the `format`, `invalid` and ordering properties are normative. The
  generator sequence documents the reference generator.

Implementation notes that the vectors pin down:

- Candidates (`GET /v1/pair/:id` and the `pair` frame) must carry `pop` (64 bytes). A missing
  or malformed `pop` is `bad_shape`; a wrong signature is `bad_pop`.
- QR parsers require `pair_id` (`c`) to be exactly 22 characters of `[A-Za-z0-9_-]` (b64 of
  16 bytes). In values they reject every raw character outside `A-Za-z0-9-._~!$'()*,;:@/?=`
  and `%XX` escapes. So a raw `+` (never a space), space, `#`, `%` without two hex digits,
  `"`, `<`, `>`, `\`, `^`, `` ` ``, `{`, `|`, `}` and any non-ASCII or control character are
  rejected, while a raw `=` inside a value is accepted. A raw `&` always ends the value.
- base64url decoding is strict: no padding, no `+` or `/`, no whitespace, canonical
  trailing bits.
- Ed25519 verification follows RFC 8032 (cofactorless, canonical encodings), not ZIP-215.
- HKDF with `salt = ""` uses the empty string (equivalent to 32 zero bytes).
- X25519 results that are all zero are rejected.
- In the rekey signature, `epk` and `ct` are the raw decoded bytes, ids are sorted by
  byte value, and `u64(kv')` is not length-prefixed (the box AAD does length-prefix it).
