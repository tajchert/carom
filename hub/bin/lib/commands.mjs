import { USAGE } from './args.mjs';
import { CliError } from './errors.mjs';
import { fetchInfo } from './http.mjs';
import { withSecretsFile } from './secrets.mjs';
import { readState, writeState } from './state.mjs';
import { buildSetupUri, detectWorkerUrl, generateSetupToken, needsWorkersSubdomain, normalizeHubUrl } from './uri.mjs';

/*
 * Every command takes (deps, options). deps = { wrangler, fetch, io: { out, err },
 * prompt, qr, sleep } so the whole flow can run against fakes in tests.
 */

const INFO_ATTEMPTS = 5;
const INFO_RETRY_MS = 3000;

export function help({ io }) {
  io.out(USAGE);
}

async function requireState(name) {
  const state = await readState(name);
  if (!state) {
    throw new CliError(`No saved state for "${name}" in ~/.config/carom-hub. Copy that file from the machine that deployed the Hub, or run "carom-hub deploy --name ${name}" (it refuses to touch a Hub that already exists and is set up).`);
  }
  return state;
}

async function ensureLoggedIn({ wrangler, io }) {
  if (await wrangler.isLoggedIn()) return;
  io.err('You are not logged in to Cloudflare. Opening the login flow...');
  if (!(await wrangler.login())) {
    throw new CliError('Cloudflare login did not complete. Run "carom-hub deploy" again to retry.');
  }
}

const SUBDOMAIN_HELP = `Your Cloudflare account has no workers.dev subdomain yet, so the Hub has no public URL.
Register one in the dashboard: Workers & Pages -> (right sidebar) Account details ->
Subdomain -> Change. Then run this command again.`;

/**
 * Deploys and saves state. With a setupToken it is sent as the SETUP_TOKEN secret; without one
 * (the Hub is claimed and the token was deleted from this computer) the deployed secret stays.
 */
async function deployHub(deps, { name, setupToken, jurisdiction }) {
  const { wrangler, io } = deps;
  await ensureLoggedIn(deps);
  const vars = jurisdiction ? { HUB_JURISDICTION: jurisdiction } : {};
  const result = setupToken
    ? await withSecretsFile({ SETUP_TOKEN: setupToken }, (secretsFile) => wrangler.deploy({ name, secretsFile, vars }))
    : await wrangler.deploy({ name, vars });

  const output = result.output ?? '';
  const hubUrl = detectWorkerUrl(`${(result.urls ?? []).join('\n')}\n${output}`, name);
  if (!hubUrl) {
    if (needsWorkersSubdomain(output)) throw new CliError(SUBDOMAIN_HELP);
    if (result.code !== 0) throw new CliError(`wrangler deploy failed (exit code ${result.code}). See its output above.`);
    throw new CliError('The deploy succeeded but no workers.dev URL was found in wrangler\'s output. Check the Worker in the Cloudflare dashboard.');
  }
  if (result.code !== 0) throw new CliError(`wrangler deploy failed (exit code ${result.code}). See its output above.`);

  // Saved before verification: the deployed secret must never exist without a local copy.
  const state = { hubUrl, ...(setupToken && { setupToken }), jurisdiction, deployedAt: new Date().toISOString() };
  await writeState(name, state);
  io.out(`Deployed: ${hubUrl}`);
  return state;
}

/** /v1/info, retried because a fresh workers.dev route can take a few seconds to answer. */
async function verifyHub(deps, hubUrl) {
  for (let attempt = 1; ; attempt++) {
    try {
      return await fetchInfo(deps.fetch, hubUrl);
    } catch (error) {
      if (attempt >= INFO_ATTEMPTS) throw error;
      await deps.sleep(INFO_RETRY_MS);
    }
  }
}

/**
 * The setup token only matters until a device claims the Hub, so once a command sees
 * `setup_done: true` it is deleted from the state file. Returns the state to use from here on.
 */
async function forgetTokenIfClaimed(name, state, info) {
  if (!info.setup_done || !state.setupToken) return state;
  const { setupToken, ...rest } = state;
  await writeState(name, rest);
  return rest;
}

async function printSetup(deps, state, info) {
  const { io } = deps;
  if (info.setup_done) {
    io.out('This Hub is already set up (claimed by a first device).');
    io.out('To add more devices, open Carom on a device that is already paired and use its "Add device" screen.');
    return;
  }
  if (!state.setupToken) {
    io.out('This Hub is waiting for its first device, but no setup token is saved on this computer.');
    io.out('Run "carom-hub reset" to start over with a new token and QR code.');
    return;
  }
  const uri = buildSetupUri(state.hubUrl, state.setupToken);
  io.out('');
  io.out(await deps.qr(uri));
  io.out(`Setup URI: ${uri}`);
  io.out('');
  io.out('Open Carom on your Mac -> Set up new hub -> scan the QR code or paste the URI.');
  io.out('The URI contains your setup token: do not share it or post it anywhere.');
  io.out('The token works once. It is deleted from this computer as soon as a device has set up the Hub.');
  io.out('To start over later, run "carom-hub reset": it needs your Cloudflare login, erases the Hub and signs out every device.');
}

/**
 * No local state: if a Hub with this name is already deployed and set up (or we cannot tell),
 * deploying would replace SETUP_TOKEN and sign out every device, so require --new-token.
 */
async function refuseToRotateExistingHub(deps, { name, newToken }) {
  const { exists, hubUrl } = await deps.wrangler.findExistingHub(name);
  if (!exists || newToken) return;
  let setupDone = true; // unknown counts as set up
  if (hubUrl) {
    try {
      setupDone = (await fetchInfo(deps.fetch, hubUrl)).setup_done !== false;
    } catch { /* keep the conservative answer */ }
  }
  if (!setupDone) return; // nobody is paired yet, so a new token signs nobody out
  throw new CliError(
    `A Hub named "${name}" already exists on your Cloudflare account${hubUrl ? ` (${hubUrl})` : ''} and is set up, but there is no saved state for it here. ` +
    `Deploying would replace its SETUP_TOKEN and sign out every device.\n` +
    `To recover: copy ~/.config/carom-hub/${name}.json from the machine that deployed it, then use "carom-hub update".\n` +
    `To start over on purpose (new token, re-pair all devices; pass the same --jurisdiction as before): carom-hub deploy --name ${name} --new-token`,
  );
}

export async function deploy(deps, options) {
  const { io } = deps;
  const existing = await readState(options.name);
  if (existing && options.jurisdiction && options.jurisdiction !== (existing.jurisdiction ?? null)) {
    throw new CliError(
      `"${options.name}" was deployed with jurisdiction ${existing.jurisdiction ?? 'none'}; it cannot be changed. ` +
      `Use another --name.`,
    );
  }
  if (!existing) await refuseToRotateExistingHub(deps, options);
  const jurisdiction = options.jurisdiction ?? existing?.jurisdiction ?? null;
  // A saved state without a token means the Hub is claimed: keep the deployed secret.
  const setupToken = existing ? existing.setupToken : generateSetupToken();

  let state = await deployHub(deps, { name: options.name, setupToken, jurisdiction });
  const info = await verifyHub(deps, state.hubUrl);
  state = await forgetTokenIfClaimed(options.name, state, info);
  io.out(`Hub URL: ${state.hubUrl} (protocol ${info.protocol}, version ${info.version})`);
  if (jurisdiction) {
    io.out(`Jurisdiction: ${jurisdiction}. Warning: it cannot be changed later without a full reset.`);
  } else {
    io.out('Warning: the jurisdiction (for example --jurisdiction eu) cannot be changed later without a full reset.');
  }
  await printSetup(deps, state, info);
}

export async function update(deps, options) {
  const existing = await requireState(options.name);
  let state = await deployHub(deps, {
    name: options.name,
    setupToken: existing.setupToken,
    jurisdiction: existing.jurisdiction ?? null,
  });
  const info = await verifyHub(deps, state.hubUrl);
  state = await forgetTokenIfClaimed(options.name, state, info);
  deps.io.out(`Updated ${state.hubUrl} (protocol ${info.protocol}, version ${info.version}).`);
}

export async function info(deps, options) {
  let hubUrl;
  let state = null;
  if (options.url) {
    try {
      hubUrl = normalizeHubUrl(options.url);
    } catch (error) {
      throw new CliError(error.message);
    }
  } else {
    state = await requireState(options.name);
    hubUrl = state.hubUrl;
  }
  const result = await fetchInfo(deps.fetch, hubUrl);
  if (state) await forgetTokenIfClaimed(options.name, state, result);
  const { io } = deps;
  io.out(`Hub:           ${hubUrl}`);
  io.out(`Protocol:      ${result.protocol}`);
  io.out(`Version:       ${result.version}`);
  io.out(`Set up:        ${result.setup_done ? 'yes (claimed)' : 'no (waiting for the first device)'}`);
  io.out(`Jurisdiction:  ${result.jurisdiction || 'none'}`);
}

export async function qr(deps, options) {
  let state = await requireState(options.name);
  const result = await fetchInfo(deps.fetch, state.hubUrl);
  state = await forgetTokenIfClaimed(options.name, state, result);
  await printSetup(deps, state, result);
}

/** The user must type the hub name; --yes skips the prompt. */
export async function confirmReset({ prompt }, name, yes) {
  if (yes) return;
  const answer = await prompt(`This erases ALL data on "${name}" (devices, keys, queued messages) and signs out every device. Type the hub name to confirm: `);
  if (answer.trim() !== name) throw new CliError('Confirmation did not match. Nothing was changed.');
}

const RESET_ATTEMPTS = 10;
const RESET_RETRY_MS = 2000;

/** Polls /v1/info until the Hub reports it is unclaimed: it erases itself on its first request under the new token. */
async function waitForWipe(deps, hubUrl) {
  for (let attempt = 1; ; attempt++) {
    try {
      const result = await fetchInfo(deps.fetch, hubUrl);
      if (result.setup_done === false) return result;
      if (attempt >= RESET_ATTEMPTS) throw new CliError('The Hub still reports it is set up after the redeploy.');
    } catch (error) {
      if (attempt >= RESET_ATTEMPTS) throw error;
    }
    await deps.sleep(RESET_RETRY_MS);
  }
}

/**
 * Reset = a new SETUP_TOKEN. The Hub notices the changed secret and erases itself, which also
 * invalidates every device token. The new token is saved before the deploy so it is never lost.
 */
export async function reset(deps, options) {
  const { io } = deps;
  let state = await requireState(options.name);
  await confirmReset(deps, options.name, options.yes);
  await ensureLoggedIn(deps);

  state = { ...state, setupToken: generateSetupToken() };
  await writeState(options.name, state);
  let result;
  try {
    state = await deployHub(deps, { name: options.name, setupToken: state.setupToken, jurisdiction: state.jurisdiction ?? null });
    result = await waitForWipe(deps, state.hubUrl);
  } catch (error) {
    if (error instanceof CliError) {
      error.message = `${error.message}\nThe new setup token is saved locally. Run "carom-hub reset --name ${options.name}" again to finish.`;
    }
    throw error;
  }
  io.out('Hub data erased and the setup token rotated: every device is signed out.');
  await printSetup(deps, state, result);
}
