import { randomBytes } from 'node:crypto';

/** SETUP_TOKEN: 32 random bytes as base64url (43 chars, above the Hub's 32-char minimum). */
export function generateSetupToken() {
  return randomBytes(32).toString('base64url');
}

/** The URI shown as a QR code (SPEC section 8). */
export function buildSetupUri(hubUrl, setupToken) {
  return `carom://setup?v=1&h=${encodeURIComponent(hubUrl)}&t=${encodeURIComponent(setupToken)}`;
}

const ANSI = /\u001b\[[0-9;?]*[ -/]*[@-~]/g;
const WORKERS_DEV_URL = /https:\/\/([a-z0-9-]+)\.([a-z0-9-]+)\.workers\.dev/gi;

/**
 * Finds the workers.dev URL in `wrangler deploy` output. Prefers the URL of this
 * worker when the output mentions several. Returns null when none is present.
 */
export function detectWorkerUrl(output, workerName) {
  const text = output.replace(ANSI, '');
  const urls = [...text.matchAll(WORKERS_DEV_URL)].map((m) => ({ url: m[0].toLowerCase(), worker: m[1].toLowerCase() }));
  const own = urls.find((u) => u.worker === workerName.toLowerCase());
  return (own ?? urls[0])?.url ?? null;
}

/** True when wrangler output says the account has no workers.dev subdomain yet. */
export function needsWorkersSubdomain(output) {
  return /workers\.dev subdomain/i.test(output.replace(ANSI, ''));
}

/** Normalizes a user-supplied hub URL to its origin; throws TypeError on garbage. */
export function normalizeHubUrl(input) {
  let url;
  try {
    url = new URL(input);
  } catch {
    throw new TypeError(`"${input}" is not a valid URL`);
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') {
    throw new TypeError(`"${input}" must be an http(s) URL`);
  }
  return url.origin;
}
