// QR / pasted-text URIs (SPEC §8):
//   carom://setup?v=1&h=<urlenc hub url>&t=<setup token>
//   carom://pair?v=1&h=<urlenc hub url>&c=<pair_id>&s=<b64 pair_secret>
//
// Builders percent-encode every byte outside the RFC 3986 unreserved set (uppercase hex).
// Parsers are strict: exact parameter set, no duplicates, valid percent-escapes, and '+'
// must be percent-encoded inside values. pair_id is exactly 22 b64url characters (b64 of 16 bytes).

import { b64decodeLength, b64encode } from './b64.ts';
import { fromUtf8, utf8, type Bytes } from './bytes.ts';

export type QrPayload =
  | { kind: 'setup'; hub: string; setupToken: string }
  | { kind: 'pair'; hub: string; pairId: string; pairSecret: Bytes };

const MIN_SETUP_TOKEN = 32;
const PAIR_ID_RE = /^[A-Za-z0-9_-]{22}$/;
const RAW_VALUE_RE = /^(?:[A-Za-z0-9\-._~!$'()*,;:@/?=]|%[0-9A-Fa-f]{2})*$/;
const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);
const CONTROL_RE = /[\u0000-\u001f\u007f]/;

function isUnreserved(byte: number): boolean {
  return (
    (byte >= 0x30 && byte <= 0x39) ||
    (byte >= 0x41 && byte <= 0x5a) ||
    (byte >= 0x61 && byte <= 0x7a) ||
    byte === 0x2d ||
    byte === 0x2e ||
    byte === 0x5f ||
    byte === 0x7e
  );
}

/** Percent-encodes all bytes except A-Z a-z 0-9 - . _ ~ */
export function percentEncode(s: string): string {
  let out = '';
  for (const byte of utf8(s)) {
    out += isUnreserved(byte) ? String.fromCharCode(byte) : '%' + byte.toString(16).toUpperCase().padStart(2, '0');
  }
  return out;
}

function percentDecode(raw: string): string | null {
  if (!RAW_VALUE_RE.test(raw)) return null;
  const bytes: number[] = [];
  for (let i = 0; i < raw.length; i++) {
    if (raw[i] === '%') {
      bytes.push(parseInt(raw.slice(i + 1, i + 3), 16));
      i += 2;
    } else {
      bytes.push(raw.charCodeAt(i));
    }
  }
  try {
    return fromUtf8(Uint8Array.from(bytes));
  } catch {
    return null;
  }
}

/** Hub base URL: https (http only for loopback hosts), no credentials, query or fragment. */
export function isValidHubUrl(s: string): boolean {
  if (CONTROL_RE.test(s) || /\s/.test(s)) return false;
  let url: URL;
  try {
    url = new URL(s);
  } catch {
    return false;
  }
  const secure = url.protocol === 'https:' || (url.protocol === 'http:' && LOOPBACK_HOSTS.has(url.hostname));
  return secure && url.hostname !== '' && url.username === '' && url.password === '' && !s.includes('?') && !s.includes('#');
}

export function isValidSetupToken(s: string): boolean {
  return s.length >= MIN_SETUP_TOKEN && !CONTROL_RE.test(s);
}

export function isValidPairId(s: string): boolean {
  return PAIR_ID_RE.test(s);
}

export function buildSetupUri(hub: string, setupToken: string): string {
  if (!isValidHubUrl(hub)) throw new RangeError('invalid hub url');
  if (!isValidSetupToken(setupToken)) throw new RangeError('invalid setup token');
  return `carom://setup?v=1&h=${percentEncode(hub)}&t=${percentEncode(setupToken)}`;
}

export function buildPairUri(hub: string, pairId: string, pairSecret: Bytes): string {
  if (!isValidHubUrl(hub)) throw new RangeError('invalid hub url');
  if (!isValidPairId(pairId)) throw new RangeError('invalid pair id');
  if (pairSecret.length !== 32) throw new RangeError('pair secret must be 32 bytes');
  return `carom://pair?v=1&h=${percentEncode(hub)}&c=${pairId}&s=${b64encode(pairSecret)}`;
}

const PARAMS = { setup: ['v', 'h', 't'], pair: ['v', 'h', 'c', 's'] } as const;

function parseQuery(query: string, allowed: readonly string[]): Map<string, string> | null {
  const params = new Map<string, string>();
  for (const part of query.split('&')) {
    const eq = part.indexOf('=');
    if (eq <= 0) return null;
    const key = part.slice(0, eq);
    const value = percentDecode(part.slice(eq + 1));
    if (!allowed.includes(key) || params.has(key) || value === null) return null;
    params.set(key, value);
  }
  return params.size === allowed.length ? params : null;
}

/** Parses pasted or scanned text (surrounding whitespace is ignored). Null when invalid. */
export function parseQrUri(text: string): QrPayload | null {
  const s = text.trim();
  const match = /^carom:\/\/(setup|pair)\?(.*)$/s.exec(s);
  if (match === null) return null;
  const kind = match[1] as 'setup' | 'pair';
  const params = parseQuery(match[2]!, PARAMS[kind]);
  if (params === null || params.get('v') !== '1') return null;
  const hub = params.get('h')!;
  if (!isValidHubUrl(hub)) return null;
  if (kind === 'setup') {
    const setupToken = params.get('t')!;
    return isValidSetupToken(setupToken) ? { kind, hub, setupToken } : null;
  }
  const pairId = params.get('c')!;
  const pairSecret = b64decodeLength(params.get('s'), 32);
  return isValidPairId(pairId) && pairSecret !== null ? { kind, hub, pairId, pairSecret } : null;
}
