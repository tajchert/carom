import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { mkdir, mkdtemp, readdir, readFile, rm, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { CliError } from './errors.mjs';

/** Resolves wrangler's JS entry point (from pkgDir or any parent, so npm workspaces work), or null. */
export function findWranglerEntry(pkgDir) {
  try {
    const require = createRequire(join(pkgDir, 'package.json'));
    const manifestPath = require.resolve('wrangler/package.json');
    const { bin } = JSON.parse(readFileSync(manifestPath, 'utf8'));
    return join(dirname(manifestPath), typeof bin === 'string' ? bin : bin.wrangler);
  } catch {
    return null;
  }
}

const copyOne = async (from, to) => writeFile(to, await readFile(from));

/** Copies the source tree without test files. Contents are re-written (not cp'd) so a read-only source yields writable files. */
async function copyTree(from, to) {
  await mkdir(to, { recursive: true });
  for (const item of await readdir(from, { withFileTypes: true })) {
    if (item.isDirectory()) await copyTree(join(from, item.name), join(to, item.name));
    else if (item.isFile() && !/\.test\.ts$/.test(item.name)) await writeFile(join(to, item.name), await readFile(join(from, item.name)));
  }
}

/** Targets from the "deploy" entries of wrangler's ND-JSON output file (WRANGLER_OUTPUT_FILE_PATH). */
export function parseDeployTargets(ndjson) {
  const targets = [];
  for (const line of ndjson.split('\n')) {
    if (!line.trim()) continue;
    let entry;
    try {
      entry = JSON.parse(line);
    } catch {
      continue;
    }
    if (entry?.type !== 'deploy' || !Array.isArray(entry.targets)) continue;
    for (const target of entry.targets) {
      if (typeof target !== 'string') continue;
      targets.push(/workers\.dev$/i.test(target) && !/^https?:\/\//i.test(target) ? `https://${target}` : target);
    }
  }
  return targets;
}

/**
 * Real wrangler runner. The interface the commands depend on is:
 *   isLoggedIn() -> boolean
 *   login() -> boolean
 *   deploy({ name, secretsFile, vars }) -> { code, urls, output }
 *   findExistingHub(name) -> { exists, hubUrl }   (hubUrl is null when it cannot be determined)
 *
 * wrangler is spawned directly (node + its JS entry, no shell, so Windows paths with spaces and
 * quotes are safe) from a writable temp copy of the package: it writes .wrangler/tmp next to
 * wrangler.jsonc, which fails for a root-owned global install.
 */
export function createWranglerRunner({ pkgDir, spawnImpl = spawn, fetchImpl = globalThis.fetch, wranglerEntry }) {
  const entry = wranglerEntry ?? findWranglerEntry(pkgDir);

  function run(args, { stdio, cwd = tmpdir(), env } = {}) {
    return new Promise((resolve, reject) => {
      if (!entry) {
        reject(new CliError('wrangler was not found. Reinstall the package ("npm i -g carom-hub") or run "npm install" in the hub folder.'));
        return;
      }
      const child = spawnImpl(process.execPath, [entry, ...args], {
        cwd,
        stdio: stdio ?? ['ignore', 'pipe', 'pipe'],
        env: env ?? process.env,
      });
      let output = '';
      child.stdout?.on('data', (c) => { output += c.toString(); });
      child.stderr?.on('data', (c) => { output += c.toString(); });
      child.on('error', reject);
      child.on('close', (code) => resolve({ code: code ?? 1, output }));
    });
  }

  /** Copies wrangler.jsonc, package.json (imported by src), tsconfig.json and src into a fresh temp dir; always removed afterwards. */
  async function withWorkDir(fn) {
    const dir = await mkdtemp(join(tmpdir(), 'carom-hub-work-'));
    try {
      await copyOne(join(pkgDir, 'wrangler.jsonc'), join(dir, 'wrangler.jsonc'));
      await copyOne(join(pkgDir, 'tsconfig.json'), join(dir, 'tsconfig.json')).catch(() => {});
      await copyOne(join(pkgDir, 'package.json'), join(dir, 'package.json'));
      await copyTree(join(pkgDir, 'src'), join(dir, 'src'));
      return await fn(dir);
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  }

  async function cloudflareSubdomain() {
    const auth = await run(['auth', 'token', '--json']);
    const who = await run(['whoami', '--json']);
    if (auth.code !== 0 || who.code !== 0) return null;
    const credentials = JSON.parse(auth.output.slice(auth.output.indexOf('{')));
    const accounts = JSON.parse(who.output.slice(who.output.indexOf('{'))).accounts ?? [];
    const accountId = process.env.CLOUDFLARE_ACCOUNT_ID ?? (accounts.length === 1 ? accounts[0].id : null);
    if (!accountId) return null;
    const headers = credentials.type === 'api_key'
      ? { 'X-Auth-Key': credentials.key, 'X-Auth-Email': credentials.email }
      : { Authorization: `Bearer ${credentials.token}` };
    const response = await fetchImpl(`https://api.cloudflare.com/client/v4/accounts/${accountId}/workers/subdomain`, {
      headers,
      signal: AbortSignal.timeout(15_000),
    });
    const body = await response.json();
    return body?.result?.subdomain ?? null;
  }

  return {
    async isLoggedIn() {
      const { code } = await run(['whoami', '--json']);
      return code === 0; // --json exits non-zero when not authenticated
    },
    async login() {
      const { code } = await run(['login'], { stdio: 'inherit' });
      return code === 0;
    },
    /**
     * Whether a Worker with this name already has a SETUP_TOKEN secret, and where it lives.
     * The URL is looked up best-effort through the account's workers.dev subdomain.
     */
    async findExistingHub(name) {
      const list = await withWorkDir((cwd) => run(['secret', 'list', '--name', name, '--format', 'json'], { cwd }));
      let secrets = null;
      try {
        secrets = JSON.parse(list.output.slice(list.output.indexOf('[')));
      } catch { /* not JSON: handled below */ }
      if (list.code !== 0 || !Array.isArray(secrets)) {
        if (/10007|not found|does not exist/i.test(list.output)) return { exists: false, hubUrl: null };
        throw new CliError(`Could not check whether "${name}" already exists (wrangler secret list failed):\n${list.output.trim()}`);
      }
      if (!secrets.some((s) => s?.name === 'SETUP_TOKEN')) return { exists: false, hubUrl: null };
      let hubUrl = null;
      try {
        const subdomain = await cloudflareSubdomain();
        if (subdomain) hubUrl = `https://${name}.${subdomain}.workers.dev`;
      } catch { /* the caller treats an unknown URL conservatively */ }
      return { exists: true, hubUrl };
    },
    /**
     * Runs with inherited stdio so wrangler can prompt (account selection, workers.dev
     * subdomain registration). The URL comes from wrangler's machine-readable output file.
     * `secretsFile` is optional: the setup token is only on disk while the Hub is unclaimed.
     */
    deploy({ name, secretsFile = null, vars = {} }) {
      return withWorkDir(async (cwd) => {
        const outputFile = join(cwd, 'wrangler-output.ndjson');
        // Without a secrets file wrangler keeps the SETUP_TOKEN already on the Worker.
        const args = ['deploy', '--name', name];
        if (secretsFile) args.push('--secrets-file', secretsFile);
        for (const [key, value] of Object.entries(vars)) args.push('--var', `${key}:${value}`);
        const { code } = await run(args, {
          cwd,
          stdio: 'inherit',
          env: { ...process.env, WRANGLER_OUTPUT_FILE_PATH: outputFile },
        });
        const urls = await readFile(outputFile, 'utf8').then(parseDeployTargets, () => []);
        return { code, urls, output: '' };
      });
    },
  };
}
