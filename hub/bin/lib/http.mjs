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
