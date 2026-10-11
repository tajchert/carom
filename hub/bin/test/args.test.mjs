import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseCli } from '../lib/args.mjs';
import { CliError } from '../lib/errors.mjs';

test('no arguments shows help', () => {
  assert.equal(parseCli([]).command, 'help');
});

test('--help and --version win over the command', () => {
  assert.equal(parseCli(['deploy', '--help']).command, 'help');
  assert.equal(parseCli(['-v']).command, 'version');
});

test('deploy options and defaults', () => {
  const { command, options } = parseCli(['deploy', '--name', 'my-hub', '--jurisdiction', 'eu', '--yes']);
  assert.equal(command, 'deploy');
  assert.deepEqual(options, { name: 'my-hub', jurisdiction: 'eu', url: null, yes: true, newToken: false });
  assert.deepEqual(parseCli(['update']).options, { name: 'carom-hub', jurisdiction: null, url: null, yes: false, newToken: false });
});

test('deploy --new-token and info --url', () => {
  assert.equal(parseCli(['deploy', '--new-token']).options.newToken, true);
  assert.equal(parseCli(['info', '--url', 'https://x.workers.dev']).options.url, 'https://x.workers.dev');
});

for (const argv of [['bogus'], ['deploy', '--nope'], ['deploy', 'extra'], ['deploy', '--name', 'Bad Name'], ['deploy', '--name', '../x'], ['deploy', '--jurisdiction', 'EU!']]) {
  test(`rejects ${argv.join(' ')} with exit code 2`, () => {
    assert.throws(() => parseCli(argv), (e) => e instanceof CliError && e.exitCode === 2);
  });
}
