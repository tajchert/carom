import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, test } from 'node:test';
import { confirmReset, deploy, info, qr, reset, update } from '../lib/commands.mjs';
import { CliError } from '../lib/errors.mjs';
import { readState, writeState } from '../lib/state.mjs';
import { readFileSync } from 'node:fs';
import { statePath } from '../lib/state.mjs';
import { INFO, SAMPLE_OUTPUT, exists, fakeFetch, fakeWrangler, jsonResponse, makeDeps, useTempHome } from './helpers.mjs';

const URL_ = 'https://carom-hub.my-sub.workers.dev';
const opts = (extra = {}) => ({ name: 'carom-hub', jurisdiction: null, url: null, yes: false, newToken: false, ...extra });
const allOutput = (deps) => deps.out.join('\n');

let temp;
beforeEach(async () => { temp = await useTempHome(); });
afterEach(() => temp.restore());

describe('deploy', () => {
  test('first deploy generates a token, saves state, prints URI and QR', async () => {
    const deps = makeDeps();
    await deploy(deps, opts({ jurisdiction: 'eu' }));

    const call = deps.wrangler.calls.deploys[0];
    assert.equal(call.name, 'carom-hub');
    assert.deepEqual(call.vars, { HUB_JURISDICTION: 'eu' });
    assert.equal(call.mode, 0o600);
    assert.match(call.secrets.SETUP_TOKEN, /^[A-Za-z0-9_-]{43}$/);

    const state = await readState('carom-hub');
    assert.equal(state.hubUrl, URL_);
    assert.equal(state.setupToken, call.secrets.SETUP_TOKEN);
    assert.equal(state.jurisdiction, 'eu');
    assert.ok(state.deployedAt);

    const text = allOutput(deps);
    const uri = `carom://setup?v=1&h=${encodeURIComponent(URL_)}&t=${state.setupToken}`;
    assert.ok(text.includes(`Setup URI: ${uri}`));
    assert.ok(text.includes(`[QR:${uri}]`));
    assert.ok(text.includes('Set up new hub'));
    assert.ok(text.includes('cannot be changed later'));
  });

  test('without local state, refuses to rotate the token of an existing, set-up hub', async () => {
    const wrangler = fakeWrangler({ existing: { exists: true, hubUrl: URL_ } });
    const fetch = fakeFetch({ 'GET /v1/info': { ...INFO, setup_done: true } });
    const deps = makeDeps({ wrangler, fetch });
    await assert.rejects(deploy(deps, opts()), /already exists[\s\S]*sign out every device[\s\S]*--new-token/);
    assert.equal(wrangler.calls.deploys.length, 0);
    assert.equal(await readState('carom-hub'), null);
  });

  test('without local state, refuses when the existing hub URL cannot be determined', async () => {
    const wrangler = fakeWrangler({ existing: { exists: true, hubUrl: null } });
    await assert.rejects(deploy(makeDeps({ wrangler }), opts()), /already exists/);
    assert.equal(wrangler.calls.deploys.length, 0);
  });

  test('without local state, --new-token overrides the existing-hub guard', async () => {
    const wrangler = fakeWrangler({ existing: { exists: true, hubUrl: URL_ } });
    const fetch = fakeFetch({ 'GET /v1/info': { ...INFO, setup_done: true } });
    await deploy(makeDeps({ wrangler, fetch }), opts({ newToken: true }));
    assert.equal(wrangler.calls.deploys.length, 1);
  });

  test('without local state, an existing but unclaimed hub may be redeployed', async () => {
    const wrangler = fakeWrangler({ existing: { exists: true, hubUrl: URL_ } });
    await deploy(makeDeps({ wrangler }), opts());
    assert.equal(wrangler.calls.deploys.length, 1);
  });

  test('omits HUB_JURISDICTION when none is requested', async () => {
    const deps = makeDeps();
    await deploy(deps, opts());
    assert.deepEqual(deps.wrangler.calls.deploys[0].vars, {});
  });

  test('re-running deploy reuses the saved token and jurisdiction', async () => {
    await deploy(makeDeps(), opts({ jurisdiction: 'eu' }));
    const first = await readState('carom-hub');
    const deps = makeDeps();
    await deploy(deps, opts());
    assert.equal(deps.wrangler.calls.deploys[0].secrets.SETUP_TOKEN, first.setupToken);
    assert.deepEqual(deps.wrangler.calls.deploys[0].vars, { HUB_JURISDICTION: 'eu' });
  });

  test('refuses to change the jurisdiction of an existing hub', async () => {
    await deploy(makeDeps(), opts({ jurisdiction: 'eu' }));
    const deps = makeDeps();
    await assert.rejects(deploy(deps, opts({ jurisdiction: 'fedramp' })), /cannot be changed/);
    assert.equal(deps.wrangler.calls.deploys.length, 0);
  });

  test('does not print the token or QR when the hub is already claimed', async () => {
    const fetch = fakeFetch({ 'GET /v1/info': { ...INFO, setup_done: true } });
    const deps = makeDeps({ fetch });
    await deploy(deps, opts());
    const { setupToken } = await readState('carom-hub');
    assert.ok(!allOutput(deps).includes(setupToken));
    assert.ok(!allOutput(deps).includes('[QR:'));
    assert.match(allOutput(deps), /already set up/);
  });

  test('logs in interactively when not authenticated', async () => {
    const deps = makeDeps({ wrangler: fakeWrangler({ loggedIn: false }) });
    await deploy(deps, opts());
    assert.equal(deps.wrangler.calls.login, 1);
    assert.equal(deps.wrangler.calls.deploys.length, 1);
  });

  test('fails when login does not complete', async () => {
    const deps = makeDeps({ wrangler: fakeWrangler({ loggedIn: false, loginOk: false }) });
    await assert.rejects(deploy(deps, opts()), /login did not complete/);
    assert.equal(deps.wrangler.calls.deploys.length, 0);
  });

  test('deletes the secrets file after success', async () => {
    const deps = makeDeps();
    await deploy(deps, opts());
    assert.equal(exists(deps.wrangler.calls.deploys[0].secretsFile), false);
  });

  test('deletes the secrets file even when wrangler throws', async () => {
    const wrangler = fakeWrangler({ deployError: new Error('boom') });
    await assert.rejects(deploy(makeDeps({ wrangler }), opts()), /boom/);
    assert.equal(exists(wrangler.calls.deploys[0].secretsFile), false);
    assert.equal(await readState('carom-hub'), null);
  });

  test('deletes the secrets file when wrangler exits non-zero', async () => {
    const wrangler = fakeWrangler({ deployResult: { code: 1, output: 'X [ERROR] nope' } });
    await assert.rejects(deploy(makeDeps({ wrangler }), opts()), /wrangler deploy failed \(exit code 1\)/);
    assert.equal(exists(wrangler.calls.deploys[0].secretsFile), false);
    assert.equal(await readState('carom-hub'), null);
  });

  test('takes the URL from the output-file targets', async () => {
    const wrangler = fakeWrangler({ deployResult: { code: 0, urls: ['https://carom-hub.other.workers.dev'], output: '' } });
    await deploy(makeDeps({ wrangler }), opts());
    assert.equal((await readState('carom-hub')).hubUrl, 'https://carom-hub.other.workers.dev');
  });

  test('explains how to register a workers.dev subdomain', async () => {
    const output = 'X [ERROR] You need to register a workers.dev subdomain before publishing to workers.dev';
    const wrangler = fakeWrangler({ deployResult: { code: 1, output } });
    await assert.rejects(deploy(makeDeps({ wrangler }), opts()), (e) => e instanceof CliError && /register|Subdomain/.test(e.message) && e.exitCode === 1);
  });

  test('errors when the URL cannot be found in a successful deploy', async () => {
    const wrangler = fakeWrangler({ deployResult: { code: 0, output: 'Deployed.' } });
    await assert.rejects(deploy(makeDeps({ wrangler }), opts()), /no workers\.dev URL/);
  });

  test('keeps the state when /v1/info verification fails', async () => {
    const fetch = fakeFetch({ 'GET /v1/info': () => jsonResponse({ hello: 'world' }) });
    await assert.rejects(deploy(makeDeps({ fetch }), opts()), /does not look like a Carom Hub/);
    assert.ok(await readState('carom-hub'));
  });

  test('retries /v1/info while the new route propagates', async () => {
    let attempts = 0;
    const fetch = fakeFetch({ 'GET /v1/info': () => (++attempts < 3 ? jsonResponse({}, 404) : jsonResponse(INFO)) });
    await deploy(makeDeps({ fetch }), opts());
    assert.equal(attempts, 3);
  });

  test('rejects a Hub speaking another protocol version', async () => {
    const fetch = fakeFetch({ 'GET /v1/info': { ...INFO, protocol: 2 } });
    await assert.rejects(deploy(makeDeps({ fetch }), opts()), /protocol 2/);
  });
});

describe('update', () => {
  test('requires prior state', async () => {
    await assert.rejects(update(makeDeps(), opts()), /Copy that file.*carom-hub deploy --name/);
  });

  test('redeploys with the same token and jurisdiction', async () => {
    await deploy(makeDeps(), opts({ jurisdiction: 'eu' }));
    const saved = await readState('carom-hub');
    const deps = makeDeps();
    await update(deps, opts());
    const call = deps.wrangler.calls.deploys[0];
    assert.equal(call.secrets.SETUP_TOKEN, saved.setupToken);
    assert.deepEqual(call.vars, { HUB_JURISDICTION: 'eu' });
    assert.equal(exists(call.secretsFile), false);
    assert.ok(!allOutput(deps).includes(saved.setupToken));
  });
});

describe('info and qr', () => {
  test('info --url prints fields without saved state', async () => {
    const deps = makeDeps({ fetch: fakeFetch({ 'GET /v1/info': { ...INFO, jurisdiction: 'eu' } }) });
    await info(deps, opts({ url: 'https://x.workers.dev/' }));
    assert.match(allOutput(deps), /Jurisdiction:\s+eu/);
    assert.equal(deps.fetch.requests[0].url, 'https://x.workers.dev/v1/info');
  });

  test('info reports a network failure helpfully', async () => {
    const fetch = async () => { throw new TypeError('fetch failed', { cause: new Error('getaddrinfo ENOTFOUND') }); };
    await assert.rejects(info(makeDeps({ fetch }), opts({ url: 'https://x.workers.dev' })), /Could not reach .*ENOTFOUND/);
  });

  test('info rejects an invalid --url', async () => {
    await assert.rejects(info(makeDeps(), opts({ url: 'nope' })), /not a valid URL/);
  });

  test('qr reprints setup while unclaimed', async () => {
    await writeState('carom-hub', { hubUrl: URL_, setupToken: 'tok', jurisdiction: null, deployedAt: 'x' });
    const deps = makeDeps();
    await qr(deps, opts());
    assert.ok(allOutput(deps).includes(`[QR:carom://setup?v=1&h=${encodeURIComponent(URL_)}&t=tok]`));
  });

  test('qr withholds the token once the hub is claimed', async () => {
    await writeState('carom-hub', { hubUrl: URL_, setupToken: 'secret-tok', jurisdiction: null, deployedAt: 'x' });
    const deps = makeDeps({ fetch: fakeFetch({ 'GET /v1/info': { ...INFO, setup_done: true } }) });
    await qr(deps, opts());
    assert.ok(!allOutput(deps).includes('secret-tok'));
    assert.match(allOutput(deps), /already set up/);
  });
});

describe('token lifetime', () => {
  const claimed = () => fakeFetch({ 'GET /v1/info': { ...INFO, setup_done: true } });
  const withToken = { hubUrl: URL_, setupToken: 'secret-tok', jurisdiction: 'eu', deployedAt: 'x' };

  test('deploy deletes the token from the state file once the hub is claimed', async () => {
    await deploy(makeDeps(), opts());
    await deploy(makeDeps({ fetch: claimed() }), opts());
    const state = await readState('carom-hub');
    assert.equal(state.setupToken, undefined);
    assert.equal(state.hubUrl, URL_);
    assert.ok(state.deployedAt);
  });

  test('update, qr and info delete the token once the hub is claimed', async () => {
    for (const run of [update, qr, info]) {
      await writeState('carom-hub', withToken);
      await run(makeDeps({ fetch: claimed() }), opts());
      assert.deepEqual(Object.keys(await readState('carom-hub')).sort(), ['deployedAt', 'hubUrl', 'jurisdiction']);
    }
  });

  test('the token stays while the hub is unclaimed', async () => {
    await writeState('carom-hub', withToken);
    await update(makeDeps(), opts());
    await info(makeDeps(), opts());
    assert.equal((await readState('carom-hub')).setupToken, 'secret-tok');
  });

  test('update and deploy without a token deploy without a secrets file', async () => {
    const { setupToken, ...tokenless } = withToken;
    for (const run of [update, deploy]) {
      await writeState('carom-hub', tokenless);
      const deps = makeDeps({ fetch: claimed() });
      await run(deps, opts());
      assert.equal(deps.wrangler.calls.deploys[0].secretsFile, undefined);
      assert.equal(deps.wrangler.calls.deploys[0].secrets, undefined);
      assert.deepEqual(deps.wrangler.calls.deploys[0].vars, { HUB_JURISDICTION: 'eu' });
      assert.equal((await readState('carom-hub')).setupToken, undefined);
    }
  });

  test('qr on an unclaimed hub without a token points at reset', async () => {
    await writeState('carom-hub', { hubUrl: URL_, jurisdiction: null, deployedAt: 'x' });
    const deps = makeDeps();
    await qr(deps, opts());
    assert.ok(!allOutput(deps).includes('[QR:'));
    assert.match(allOutput(deps), /carom-hub reset/);
  });

  test('the setup output explains the token lifetime and reset', async () => {
    const deps = makeDeps();
    await deploy(deps, opts());
    assert.match(allOutput(deps), /works once[\s\S]*deleted from this computer[\s\S]*carom-hub reset[\s\S]*Cloudflare login[\s\S]*signs out every device/);
  });
});

describe('reset', () => {
  const saved = { hubUrl: URL_, jurisdiction: null, deployedAt: 'x' };

  test('confirmReset accepts the exact hub name and rejects anything else', async () => {
    await confirmReset(makeDeps({ answers: ['carom-hub '] }), 'carom-hub', false);
    await assert.rejects(confirmReset(makeDeps({ answers: ['yes'] }), 'carom-hub', false), /did not match/);
    await assert.rejects(confirmReset(makeDeps({ answers: [''] }), 'carom-hub', false), /did not match/);
  });

  test('confirmReset with --yes does not prompt', async () => {
    const deps = makeDeps();
    await confirmReset(deps, 'carom-hub', true);
    assert.equal(deps.prompts.length, 0);
  });

  test('wrong confirmation changes nothing', async () => {
    await writeState('carom-hub', saved);
    const deps = makeDeps({ answers: ['wrong'] });
    await assert.rejects(reset(deps, opts()), CliError);
    assert.equal(deps.fetch.requests.length, 0);
    assert.equal(deps.wrangler.calls.deploys.length, 0);
    assert.deepEqual(await readState('carom-hub'), saved);
  });

  test('always rotates: saves a new token, deploys it, waits for the wipe, shows the QR', async () => {
    await writeState('carom-hub', { ...saved, setupToken: 'old-token' });
    let polls = 0;
    const fetch = fakeFetch({ 'GET /v1/info': () => jsonResponse({ ...INFO, setup_done: ++polls < 3 }) });
    let stateAtDeploy;
    const wrangler = fakeWrangler({ onDeploy: () => { stateAtDeploy = JSON.parse(readFileSync(statePath('carom-hub'), 'utf8')); } });
    const deps = makeDeps({ wrangler, fetch, answers: ['carom-hub'] });
    await reset(deps, opts());

    const token = wrangler.calls.deploys[0].secrets.SETUP_TOKEN;
    assert.notEqual(token, 'old-token');
    assert.match(token, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(stateAtDeploy.setupToken, token);
    assert.equal((await readState('carom-hub')).setupToken, token);
    assert.equal(polls, 3);
    assert.equal(fetch.requests.some((r) => r.key === 'POST /v1/reset'), false);
    assert.ok(allOutput(deps).includes(`[QR:carom://setup?v=1&h=${encodeURIComponent(URL_)}&t=${token}]`));
  });

  test('works for a hub whose token was already deleted', async () => {
    await writeState('carom-hub', saved);
    const deps = makeDeps({ answers: ['carom-hub'] });
    await reset(deps, opts());
    assert.match(deps.wrangler.calls.deploys[0].secrets.SETUP_TOKEN, /^[A-Za-z0-9_-]{43}$/);
  });

  test('--yes skips the prompt', async () => {
    await writeState('carom-hub', saved);
    const deps = makeDeps();
    await reset(deps, opts({ yes: true }));
    assert.equal(deps.prompts.length, 0);
  });

  test('a failed deploy keeps the new token and explains how to retry', async () => {
    await writeState('carom-hub', saved);
    const wrangler = fakeWrangler({ deployResult: { code: 1, output: 'X [ERROR] nope' } });
    await assert.rejects(reset(makeDeps({ wrangler }), opts({ yes: true })), /saved locally[\s\S]*carom-hub reset --name carom-hub/);
    assert.match((await readState('carom-hub')).setupToken, /^[A-Za-z0-9_-]{43}$/);
  });

  test('gives up when the hub never reports being wiped', async () => {
    await writeState('carom-hub', saved);
    const fetch = fakeFetch({ 'GET /v1/info': { ...INFO, setup_done: true } });
    await assert.rejects(reset(makeDeps({ fetch }), opts({ yes: true })), /still reports it is set up/);
  });

  test('requires saved state', async () => {
    await assert.rejects(reset(makeDeps(), opts({ yes: true })), /Copy that file.*carom-hub deploy --name/);
  });
});
