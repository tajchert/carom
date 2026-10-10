import { CliError } from './errors.mjs';

const TIMEOUT_MS = 15_000;

async function request(fetchImpl, url, init) {
  try {
    return await fetchImpl(url, { ...init, signal: AbortSignal.timeout(TIMEOUT_MS) });
  } catch (error) {
    const reason = error.cause?.message ?? error.message;
    throw new CliError(`Could not reach ${url} (${reason}). Check your network connection and the URL.`);
  }
}

/** GET /v1/info; throws unless the URL answers like a Carom Hub speaking protocol 1. */
export async function fetchInfo(fetchImpl, hubUrl) {
  const response = await request(fetchImpl, `${hubUrl}/v1/info`);
  const info = await response.json().catch(() => null);
  if (!response.ok || info?.name !== 'carom-hub') {
    throw new CliError(`${hubUrl} does not look like a Carom Hub (no valid /v1/info answer).`);
  }
  if (info.protocol !== 1) {
    throw new CliError(`${hubUrl} speaks protocol ${info.protocol}; this carom-hub CLI supports protocol 1. Update the CLI or the Hub.`);
  }
  return info;
}

/** POST /v1/reset; maps the Hub's error codes to actionable messages. */
export async function postReset(fetchImpl, hubUrl, setupToken) {
  const response = await request(fetchImpl, `${hubUrl}/v1/reset`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ setup_token: setupToken, confirm: 'erase everything' }),
  });
  if (response.ok) return;
  const body = await response.json().catch(() => null);
  const code = body?.error?.code;
  if (code === 'forbidden' || code === 'unauthorized') {
    throw new CliError('The Hub rejected the saved setup token: it does not match the deployed SETUP_TOKEN secret.');
  }
  if (code === 'rate_limited') {
    throw new CliError('Too many failed reset attempts; the Hub locked this route for an hour. Try again later.');
  }
  throw new CliError(`Reset failed: HTTP ${response.status}${body?.error?.message ? ` (${body.error.message})` : ''}.`);
}
