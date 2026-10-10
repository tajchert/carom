import { describe, expect, it } from "vitest";
import { createVapidJwt, encryptPayload, generateVapidKeys, importVapidKeys, pointToJwk } from "../src/webpush";
import { unb64 } from "./helpers";

// RFC 8291 Appendix A (whitespace removed).
const RFC = {
  plaintext: "V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24",
  asPublic: "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8",
  asPrivate: "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw",
  uaPublic: "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
  salt: "DGv6ra1nlYgDCS1FRnbzlw",
  auth: "BTBZMqHH6r4Tts7J_aSIgg",
  header: "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8",
  ciphertext: "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ",
};

describe("RFC 8291 aes128gcm encryption", () => {
  it("reproduces the Appendix A example", async () => {
    const asPublic = unb64(RFC.asPublic);
    const privateKey = await crypto.subtle.importKey(
      "jwk",
      { kty: "EC", crv: "P-256", d: RFC.asPrivate, ...pointToJwk(asPublic) },
      { name: "ECDH", namedCurve: "P-256" },
      false,
      ["deriveBits"],
    );
    const body = await encryptPayload({ p256dh: unb64(RFC.uaPublic), auth: unb64(RFC.auth) }, unb64(RFC.plaintext), {
      serverKeys: { privateKey, publicRaw: asPublic },
      salt: unb64(RFC.salt),
    });
    const header = unb64(RFC.header);
    expect(header.length).toBe(86);
    expect(Array.from(body.slice(0, 86))).toEqual(Array.from(header));
    expect(Array.from(body.slice(86))).toEqual(Array.from(unb64(RFC.ciphertext)));
  });

  it("uses a fresh salt and server key by default", async () => {
    const sub = { p256dh: unb64(RFC.uaPublic), auth: unb64(RFC.auth) };
    const one = await encryptPayload(sub, new Uint8Array([1]));
    const two = await encryptPayload(sub, new Uint8Array([1]));
    expect(one.slice(0, 16)).not.toEqual(two.slice(0, 16));
    expect(one.slice(21, 86)).not.toEqual(two.slice(21, 86));
  });
});

describe("RFC 8292 VAPID JWT", () => {
  it("is an ES256 JWT that verifies with the public key", async () => {
    const stored = await generateVapidKeys();
    const keys = await importVapidKeys(stored);
    const exp = Math.floor(Date.now() / 1000) + 12 * 3600;
    const jwt = await createVapidJwt(keys.privateKey, "https://push.example.com", "https://hub.example.test", exp);
    const [h, p, s] = jwt.split(".") as [string, string, string];
    expect(JSON.parse(new TextDecoder().decode(unb64(h)))).toEqual({ typ: "JWT", alg: "ES256" });
    expect(JSON.parse(new TextDecoder().decode(unb64(p)))).toEqual({ aud: "https://push.example.com", exp, sub: "https://hub.example.test" });
    const publicKey = await crypto.subtle.importKey("raw", unb64(stored.publicKey), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    const valid = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, publicKey, unb64(s), new TextEncoder().encode(`${h}.${p}`));
    expect(unb64(s).length).toBe(64);
    expect(valid).toBe(true);
  });
});
