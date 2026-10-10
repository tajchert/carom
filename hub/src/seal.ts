// Byte layout of a seal (SPEC §2.3). The Hub only rebuilds it to verify signatures.

import { concat, lp, u64 } from "./encoding";

/** aad = lp(tag) || lp(H[0]) || lp(H[1]) || ... || lp(u64(kv)) || lp(salt) */
export function sealAad(tag: string, headers: readonly Uint8Array[], kv: number, salt: Uint8Array): Uint8Array {
  return concat(lp(tag), ...headers.map((h) => lp(h)), lp(u64(kv)), lp(salt));
}

/** The bytes covered by `sig`: aad || lp(ct). */
export function sealSigInput(aad: Uint8Array, ct: Uint8Array): Uint8Array {
  return concat(aad, lp(ct));
}
