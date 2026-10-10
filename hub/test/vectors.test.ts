// Cross-checks the Hub's own byte layouts and checks against the normative vectors that
// protocol/ts (an independent implementation) generates.

import { describe, expect, it } from "vitest";
import deviceIdVectors from "../../protocol/vectors/device_id.json";
import sealVectors from "../../protocol/vectors/envelope_seal.json";
import shapeVectors from "../../protocol/vectors/envelope_shape.json";
import recordVectors from "../../protocol/vectors/record.json";
import tokenVectors from "../../protocol/vectors/token.json";
import { parseEnvelope, verifyEnvelope } from "../src/envelope";
import { HttpError } from "../src/errors";
import { deviceIdFor } from "../src/ids";
import { parseRecord } from "../src/record";
import { tokenHash, verifyTokenMac } from "../src/token";

function hex(text: string): Uint8Array {
  return Uint8Array.from(text.match(/../g) ?? [], (h) => parseInt(h, 16));
}

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

function b64ToHex(text: string): string {
  const std = text.replace(/-/g, "+").replace(/_/g, "/");
  return toHex(Uint8Array.from(atob(std + "=".repeat((4 - (std.length % 4)) % 4)), (c) => c.charCodeAt(0)));
}

describe("protocol vectors", () => {
  it("derives device ids like the reference", async () => {
    for (const v of deviceIdVectors.identities) {
      expect(await deviceIdFor(hex(v.sign_pub_hex))).toBe(v.expected_device_id);
    }
  });

  it("verifies every sealed envelope", async () => {
    for (const v of sealVectors.cases) {
      const parsed = parseEnvelope(JSON.parse(v.envelope_json));
      expect(await verifyEnvelope(parsed, hex(v.sender_sign_pub_hex)), v.name).toBe(true);
    }
  });

  it("rejects an envelope whose signed header was changed", async () => {
    const v = sealVectors.cases[0]!;
    const tampered = { ...JSON.parse(v.envelope_json), ttl_s: 61 };
    expect(await verifyEnvelope(parseEnvelope(tampered), hex(v.sender_sign_pub_hex))).toBe(false);
  });

  it("applies the same shape rules", () => {
    // Cases with serialized_bytes test the raw request length, which the Worker caps
    // before the Hub sees the body; the Hub measures the compact re-serialization.
    for (const v of shapeVectors.cases.filter((c) => !("serialized_bytes" in c))) {
      let got = "ok";
      try {
        parseEnvelope(v.envelope);
      } catch (err) {
        if (!(err instanceof HttpError)) throw err;
        got = err.code;
      }
      expect(got, v.name).toBe(v.expected);
    }
  });

  it("accepts the reference sealed record", async () => {
    const sealed = recordVectors.seal.sealed as { id: string };
    const signPub = recordVectors.seal.record.sign_pub;
    const parsed = await parseRecord(sealed, sealed.id, hex(b64ToHex(signPub)));
    expect(parsed.id).toBe(sealed.id);
  });

  it("verifies token MACs and hashes like the reference", async () => {
    for (const v of tokenVectors.verify) {
      expect(await verifyTokenMac(v.setup_token, v.token), v.name).toBe(v.expected_device_id);
    }
    for (const v of tokenVectors.make) {
      expect(await verifyTokenMac(tokenVectors.setup_token, v.token)).toBe(v.device_id);
      expect(b64ToHex(await tokenHash(v.token))).toBe(v.token_hash_hex);
    }
    for (const v of tokenVectors.malformed) {
      expect(await verifyTokenMac(tokenVectors.setup_token, v.token), v.reason).toBeNull();
    }
  });
});
