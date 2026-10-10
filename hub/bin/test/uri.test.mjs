import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildSetupUri, detectWorkerUrl, generateSetupToken, needsWorkersSubdomain, normalizeHubUrl } from '../lib/uri.mjs';
import { SAMPLE_OUTPUT } from './helpers.mjs';

test('setup token is 43 chars of base64url and unique', () => {
  const token = generateSetupToken();
  assert.match(token, /^[A-Za-z0-9_-]{43}$/);
  assert.notEqual(token, generateSetupToken());
});

test('setup URI url-encodes the hub URL', () => {
  const uri = buildSetupUri('https://carom-hub.me.workers.dev', 'abc_-123');
  assert.equal(uri, 'carom://setup?v=1&h=https%3A%2F%2Fcarom-hub.me.workers.dev&t=abc_-123');
  const parsed = new URL(uri);
  assert.equal(parsed.searchParams.get('h'), 'https://carom-hub.me.workers.dev');
  assert.equal(parsed.searchParams.get('t'), 'abc_-123');
});

test('detects the URL in realistic wrangler output', () => {
  assert.equal(detectWorkerUrl(SAMPLE_OUTPUT, 'carom-hub'), 'https://carom-hub.my-sub.workers.dev');
});

test('detects the URL through ANSI colors and prefers this worker', () => {
  const output = '\u001b[32mhttps://other.my-sub.workers.dev\u001b[0m\n  \u001b[1mhttps://my-hub.my-sub.workers.dev\u001b[0m\n';
  assert.equal(detectWorkerUrl(output, 'my-hub'), 'https://my-hub.my-sub.workers.dev');
});

test('returns null when no workers.dev URL is present', () => {
  assert.equal(detectWorkerUrl('Uploaded carom-hub\nhttps://example.com/docs', 'carom-hub'), null);
});

test('recognizes the missing workers.dev subdomain error', () => {
  const output = 'X [ERROR] You need to register a workers.dev subdomain before publishing to workers.dev';
  assert.equal(needsWorkersSubdomain(output), true);
  assert.equal(needsWorkersSubdomain(SAMPLE_OUTPUT), false);
});

test('normalizeHubUrl keeps the origin and rejects non-URLs', () => {
  assert.equal(normalizeHubUrl('https://a.workers.dev/some/path/'), 'https://a.workers.dev');
  assert.throws(() => normalizeHubUrl('not a url'), TypeError);
  assert.throws(() => normalizeHubUrl('ftp://a.dev'), TypeError);
});
