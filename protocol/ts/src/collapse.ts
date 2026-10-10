// Collapse values (SPEC §3.2).

import { b64encode } from './b64.ts';
import { concat, lp, type Bytes } from './bytes.ts';
import { collapseKey } from './kdf.ts';
import { hmacSha256 } from './primitives.ts';

/** HMAC input `lp(from) || lp(real_key)`. */
export function collapseInput(from: string, realKey: string): Bytes {
  return concat(lp(from), lp(realKey));
}

/** `collapse = b64(HMAC-SHA256(K_collapse(K), lp(from) || lp(real_key))[0..16])` */
export function collapseValue(groupKey: Bytes, from: string, realKey: string): string {
  return b64encode(hmacSha256(collapseKey(groupKey), collapseInput(from, realKey)).subarray(0, 16));
}
