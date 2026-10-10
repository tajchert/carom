import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { CliError } from './errors.mjs';

/** Finds node_modules/.bin/wrangler in pkgDir or any parent (npm workspaces hoist it). */
export function findLocalWrangler(pkgDir) {
  const binName = process.platform === 'win32' ? 'wrangler.cmd' : 'wrangler';
  for (let dir = pkgDir; ; dir = dirname(dir)) {
    const candidate = join(dir, 'node_modules', '.bin', binName);
    if (existsSync(candidate)) return candidate;
    if (dirname(dir) === dir) return null;
  }
}

/**
 * Real wrangler runner. The interface the commands depend on is:
 *   isLoggedIn() -> boolean
 *   login() -> boolean
 *   deploy({ name, secretsFile, vars }) -> { code, output }
 */
export function createWranglerRunner({ pkgDir, spawnImpl = spawn }) {
  const local = findLocalWrangler(pkgDir);
  const [command, prefix] = local ? [local, []] : ['npx', ['--no-install', 'wrangler']];

  function run(args, stdio, onChunk) {
    return new Promise((resolve, reject) => {
      const child = spawnImpl(command, [...prefix, ...args], { cwd: pkgDir, stdio, shell: process.platform === 'win32' });
      let output = '';
      const collect = (chunk) => {
        output += chunk;
        onChunk?.(chunk);
      };
      child.stdout?.on('data', (c) => collect(c.toString()));
      child.stderr?.on('data', (c) => collect(c.toString()));
      child.on('error', (error) => {
        reject(error.code === 'ENOENT'
          ? new CliError('wrangler was not found. Reinstall the package ("npm i -g carom-hub") or run "npm install" in the hub folder.')
          : error);
      });
      child.on('close', (code) => resolve({ code: code ?? 1, output }));
    });
  }

  return {
    async isLoggedIn() {
      const { code } = await run(['whoami', '--json'], ['ignore', 'pipe', 'pipe']);
      return code === 0; // --json exits non-zero when not authenticated
    },
    async login() {
      const { code } = await run(['login'], 'inherit');
      return code === 0;
    },
    /** Streams wrangler's output to the terminal while capturing it for URL detection. */
    deploy({ name, secretsFile, vars = {} }) {
      const args = ['deploy', '--name', name, '--secrets-file', secretsFile];
      for (const [key, value] of Object.entries(vars)) args.push('--var', `${key}:${value}`);
      return run(args, ['inherit', 'pipe', 'pipe'], (chunk) => process.stderr.write(chunk));
    },
  };
}
