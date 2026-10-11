import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { createWranglerRunner, parseDeployTargets } from '../lib/wrangler.mjs';

// A fake wrangler entry: records cwd/args, writes .wrangler/tmp like the real one, and emits an output file.
const FAKE = `
import { appendFileSync, existsSync, mkdirSync } from 'node:fs';
const args = process.argv.slice(2);
appendFileSync(process.env.FAKE_LOG, JSON.stringify({ args, cwd: process.cwd(), hasConfig: existsSync('wrangler.jsonc'), hasPkg: existsSync('package.json'), hasSrc: existsSync('src/index.ts'), hasTests: existsSync('src/a.test.ts') }) + '\\n');
if (args[0] === 'deploy') {
  mkdirSync('.wrangler/tmp', { recursive: true });
  appendFileSync(process.env.WRANGLER_OUTPUT_FILE_PATH, JSON.stringify({ type: 'deploy', targets: ['carom-hub.my-sub.workers.dev'] }) + '\\n');
}
`;

function setup() {
  const root = mkdtempSync(join(tmpdir(), 'carom-wr-test-'));
  const pkg = join(root, 'pkg with space');
  mkdirSync(join(pkg, 'src'), { recursive: true });
  writeFileSync(join(pkg, 'wrangler.jsonc'), '{}');
  writeFileSync(join(pkg, 'tsconfig.json'), '{}');
  writeFileSync(join(pkg, 'package.json'), '{}');
  writeFileSync(join(pkg, 'src', 'index.ts'), '');
  writeFileSync(join(pkg, 'src', 'a.test.ts'), '');
  const entry = join(root, 'fake wrangler.mjs');
  writeFileSync(entry, FAKE);
  const log = join(root, 'log.ndjson');
  writeFileSync(log, '');
  process.env.FAKE_LOG = log;
  return {
    pkg,
    entry,
    log,
    cleanup: () => {
      chmodSync(pkg, 0o755);
      chmodSync(join(pkg, 'src'), 0o755);
      rmSync(root, { recursive: true, force: true });
    },
  };
}

test('deploy works with a read-only package dir, inherits stdio and reads the URL from the output file', async () => {
  const t = setup();
  try {
    chmodSync(join(t.pkg, 'src'), 0o555);
    chmodSync(t.pkg, 0o555);
    const seen = [];
    const spawnImpl = (cmd, args, options) => { seen.push({ cmd, args, options }); return spawn(cmd, args, options); };
    const runner = createWranglerRunner({ pkgDir: t.pkg, wranglerEntry: t.entry, spawnImpl });
    const result = await runner.deploy({ name: 'carom-hub', secretsFile: '/s p/secrets.json', vars: { HUB_JURISDICTION: 'eu' } });
    assert.equal(result.code, 0);
    assert.deepEqual(result.urls, ['https://carom-hub.my-sub.workers.dev']);

    const [call] = readFileSync(t.log, 'utf8').trim().split('\n').map((l) => JSON.parse(l));
    assert.notEqual(call.cwd, t.pkg);
    assert.ok(call.hasConfig && call.hasSrc && call.hasPkg);
    assert.equal(call.hasTests, false);
    assert.deepEqual(call.args, ['deploy', '--name', 'carom-hub', '--secrets-file', '/s p/secrets.json', '--var', 'HUB_JURISDICTION:eu']);
    assert.equal(existsSync(call.cwd), false); // work dir cleaned up

    assert.equal(seen[0].options.stdio, 'inherit');
    assert.equal(seen[0].options.shell, undefined);
    assert.equal(seen[0].cmd, process.execPath);
    assert.ok(seen[0].options.env.WRANGLER_OUTPUT_FILE_PATH);
    assert.equal(existsSync(join(t.pkg, '.wrangler')), false);
  } finally {
    t.cleanup();
  }
});

test('deploy without a secrets file passes no --secrets-file', async () => {
  const t = setup();
  try {
    const runner = createWranglerRunner({ pkgDir: t.pkg, wranglerEntry: t.entry });
    await runner.deploy({ name: 'carom-hub', vars: {} });
    const [call] = readFileSync(t.log, 'utf8').trim().split('\n').map((l) => JSON.parse(l));
    assert.deepEqual(call.args, ['deploy', '--name', 'carom-hub']);
  } finally {
    t.cleanup();
  }
});

test('parseDeployTargets reads ND-JSON deploy entries and adds https:// to workers.dev hosts', () => {
  const text = [
    JSON.stringify({ type: 'wrangler-session', version: 1 }),
    'not json',
    JSON.stringify({ type: 'deploy', version: 1, targets: ['a.b.workers.dev', 'https://c.d.workers.dev', 'example.com/*'] }),
    '',
  ].join('\n');
  assert.deepEqual(parseDeployTargets(text), ['https://a.b.workers.dev', 'https://c.d.workers.dev', 'example.com/*']);
});

test('findExistingHub reports a missing worker and a worker with SETUP_TOKEN', async () => {
  const t = setup();
  try {
    const run = (code, output) => createWranglerRunner({
      pkgDir: t.pkg,
      wranglerEntry: '/x',
      spawnImpl: (cmd, args) => spawn(process.execPath, ['-e',
        args[1] === 'secret' ? `process.stdout.write(${JSON.stringify(output)}); process.exit(${code})` : 'process.exit(1)']),
    });
    assert.deepEqual(await run(0, '[{"name":"SETUP_TOKEN","type":"secret_text"}]').findExistingHub('carom-hub'), { exists: true, hubUrl: null });
    assert.deepEqual(await run(0, '[]').findExistingHub('carom-hub'), { exists: false, hubUrl: null });
    assert.deepEqual(await run(1, 'X [ERROR] This Worker does not exist [code: 10007]').findExistingHub('carom-hub'), { exists: false, hubUrl: null });
    await assert.rejects(run(1, 'network down').findExistingHub('carom-hub'), /Could not check/);
  } finally {
    t.cleanup();
  }
});
