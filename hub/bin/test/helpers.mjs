import { existsSync, readFileSync, statSync } from 'node:fs';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

/** Points HOME at a fresh temp dir so state files never touch the real one. */
export async function useTempHome() {
  const previous = process.env.HOME;
  const home = await mkdtemp(join(tmpdir(), 'carom-hub-test-'));
  process.env.HOME = home;
  return { home, restore: () => { process.env.HOME = previous; } };
}

export const SAMPLE_OUTPUT = `
 ⛅️ wrangler 4.149.0
─────────────────────────────────────────────
Total Upload: 120.35 KiB / gzip: 28.10 KiB
Worker Startup Time: 12 ms
Your Worker has access to the following bindings:
Uploaded carom-hub (3.21 sec)
Deployed carom-hub triggers (1.02 sec)
  https://carom-hub.my-sub.workers.dev
Current Version ID: 7d1a2c3e-0000-4000-8000-000000000000
`;

/** Fake wrangler runner. `deploys` records each call, including what the secrets file held. */
export function fakeWrangler({ loggedIn = true, loginOk = true, deployResult = { code: 0, output: SAMPLE_OUTPUT }, deployError, existing = { exists: false, hubUrl: null }, onDeploy } = {}) {
  const calls = { login: 0, deploys: [], existing: [] };
  return {
    calls,
    async isLoggedIn() { return loggedIn; },
    async login() { calls.login++; return loginOk; },
    async findExistingHub(name) { calls.existing.push(name); return existing; },
    async deploy({ name, secretsFile, vars }) {
      calls.deploys.push({
        name,
        vars,
        secretsFile,
        secrets: JSON.parse(readFileSync(secretsFile, 'utf8')),
        mode: statSync(secretsFile).mode & 0o777,
      });
      onDeploy?.();
      if (deployError) throw deployError;
      return typeof deployResult === 'function' ? deployResult(calls.deploys.length) : deployResult;
    },
  };
}

export function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

export const INFO = { name: 'carom-hub', protocol: 1, version: '0.1.0', setup_done: false, jurisdiction: '' };

/** Fake fetch backed by a route table: "METHOD /path" -> handler or response body. */
export function fakeFetch(routes) {
  const requests = [];
  const fn = async (url, init = {}) => {
    const { pathname } = new URL(url);
    const key = `${init.method ?? 'GET'} ${pathname}`;
    requests.push({ key, url, body: init.body ? JSON.parse(init.body) : undefined });
    const route = routes[key];
    if (!route) return jsonResponse({ error: { code: 'not_found', message: 'no route' } }, 404);
    return typeof route === 'function' ? route() : jsonResponse(route);
  };
  fn.requests = requests;
  return fn;
}

export function makeDeps({ wrangler = fakeWrangler(), fetch = fakeFetch({ 'GET /v1/info': INFO }), answers = [] } = {}) {
  const out = [];
  const err = [];
  const prompts = [];
  return {
    wrangler,
    fetch,
    io: { out: (t) => out.push(t), err: (t) => err.push(t) },
    prompt: async (question) => { prompts.push(question); return answers.shift() ?? ''; },
    qr: async (text) => `[QR:${text}]`,
    sleep: async () => {},
    out,
    err,
    prompts,
  };
}

export const exists = existsSync;
