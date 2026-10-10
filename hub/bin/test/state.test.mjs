import assert from 'node:assert/strict';
import { statSync } from 'node:fs';
import { after, before, test } from 'node:test';
import { readState, stateDir, statePath, writeState } from '../lib/state.mjs';
import { useTempHome } from './helpers.mjs';

let temp;
before(async () => { temp = await useTempHome(); });
after(() => temp.restore());

test('missing state reads as null', async () => {
  assert.equal(await readState('nothing'), null);
});

test('state round-trips and is stored under HOME with private permissions', async () => {
  const state = { hubUrl: 'https://h.x.workers.dev', setupToken: 't', jurisdiction: 'eu', deployedAt: '2026-01-01T00:00:00.000Z' };
  await writeState('hub-a', state);
  assert.deepEqual(await readState('hub-a'), state);
  assert.ok(statePath('hub-a').startsWith(temp.home));
  assert.equal(statSync(stateDir()).mode & 0o777, 0o700);
  assert.equal(statSync(statePath('hub-a')).mode & 0o777, 0o600);
});

test('overwriting repairs loose permissions', async () => {
  const { chmodSync } = await import('node:fs');
  await writeState('hub-b', { a: 1 });
  chmodSync(statePath('hub-b'), 0o644);
  await writeState('hub-b', { a: 2 });
  assert.equal(statSync(statePath('hub-b')).mode & 0o777, 0o600);
});
