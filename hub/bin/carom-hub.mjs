#!/usr/bin/env node
import { execFile } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createInterface } from 'node:readline/promises';
import { setTimeout as sleep } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { parseCli } from './lib/args.mjs';
import * as commands from './lib/commands.mjs';
import { CliError } from './lib/errors.mjs';
import { createWranglerRunner } from './lib/wrangler.mjs';

// bin/ lives inside the npm package root, which also holds wrangler.jsonc.
const pkgDir = fileURLToPath(new URL('..', import.meta.url));

function packageVersion() {
  try {
    return JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version;
  } catch {
    return 'unknown';
  }
}

async function renderQr(text) {
  const { default: qrcode } = await import('qrcode-terminal');
  return new Promise((resolve) => qrcode.generate(text, { small: true }, resolve));
}

/** Opens the setup link in the Carom Mac app only (-b), so no other app claiming the scheme sees the token. */
function openInCarom(uri) {
  return new Promise((resolve) => {
    try {
      execFile('open', ['-b', 'com.mtajchert.carom.mac', uri], (error) => resolve(!error));
    } catch {
      resolve(false);
    }
  });
}

async function prompt(question) {
  const rl = createInterface({ input: process.stdin, output: process.stderr });
  try {
    return await rl.question(question);
  } finally {
    rl.close();
  }
}

async function main(argv) {
  const { command, options } = parseCli(argv);
  if (command === 'version') {
    console.log(packageVersion());
    return;
  }
  const deps = {
    wrangler: createWranglerRunner({ pkgDir }),
    fetch: globalThis.fetch,
    io: { out: (text) => console.log(text), err: (text) => console.error(text) },
    prompt,
    qr: renderQr,
    sleep,
    openApp: openInCarom,
    platform: process.platform,
  };
  await commands[command](deps, options);
}

main(process.argv.slice(2)).catch((error) => {
  if (error instanceof CliError) {
    console.error(`Error: ${error.message}`);
    process.exitCode = error.exitCode;
  } else {
    console.error(error);
    process.exitCode = 1;
  }
});
