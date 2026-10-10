import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { test } from 'node:test';

const secretsUrl = new URL('../lib/secrets.mjs', import.meta.url).href;

// Runs withSecretsFile in a child, reports the file path, then hangs until signalled.
function runChild() {
  const code = `import { withSecretsFile } from ${JSON.stringify(secretsUrl)};
    await withSecretsFile({ SETUP_TOKEN: 'x' }, (f) => { console.log(f); return new Promise(() => setInterval(() => {}, 1000)); });`;
  const child = spawn(process.execPath, ['--input-type=module', '-e', code], { stdio: ['ignore', 'pipe', 'inherit'] });
  const file = new Promise((resolve) => child.stdout.once('data', (d) => resolve(d.toString().trim())));
  const exit = new Promise((resolve) => child.on('close', (code) => resolve(code)));
  return { child, file, exit };
}

for (const [signal, status] of [['SIGINT', 130], ['SIGTERM', 143]]) {
  test(`secrets file is removed on ${signal}`, async () => {
    const { child, file, exit } = runChild();
    const path = await file;
    assert.ok(existsSync(path));
    child.kill(signal);
    assert.equal(await exit, status);
    assert.equal(existsSync(path), false);
  });
}
