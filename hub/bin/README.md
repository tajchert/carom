# carom-hub CLI

Deploys your own Carom Hub to your Cloudflare account. No config files, no build step (Node >= 22).

```
npx carom-hub deploy                  # log in if needed, deploy, print the setup QR
npx carom-hub deploy --jurisdiction eu
npx carom-hub update                  # redeploy new code, same token and settings
npx carom-hub info                    # show /v1/info
npx carom-hub qr                      # reprint the setup QR (only while unclaimed)
npx carom-hub reset                   # new setup token + erase the Hub (asks you to type the hub name)
```

Options: `--name <name>` (default `carom-hub`), `--jurisdiction <j>`, `--url <url>` (info), `--new-token` (deploy over an existing Hub with no saved state), `-y/--yes`.

## How it works

- Runs the package-local `wrangler` (spawned directly through node, no shell) from a temporary copy of
  `wrangler.jsonc`, `package.json`, `tsconfig.json` and `src`, so it also works when the package directory is
  read-only (for example a root-owned `npm i -g`). The copy is removed afterwards.
- `wrangler deploy` runs with inherited stdio, so wrangler can ask you to pick an account or register a
  workers.dev subdomain. The deployed URL is read from wrangler's output file (`WRANGLER_OUTPUT_FILE_PATH`,
  ND-JSON `deploy` entries with `targets`).
- `deploy` without saved state first checks whether a Worker with that name already has a `SETUP_TOKEN`. If it
  does and the Hub is set up (or its state cannot be read), it refuses, because a new token would sign out every
  device. Copy `~/.config/carom-hub/<name>.json` from the machine that deployed it, or start over on purpose with
  `deploy --new-token` (use the same `--jurisdiction` as before).
- Generates `SETUP_TOKEN` (32 random bytes, base64url) and passes it via a temporary `0600` secrets file
  (`wrangler deploy --secrets-file`), deleted right after the deploy (also on Ctrl-C, SIGTERM and exit). The jurisdiction goes in as
  `--var HUB_JURISDICTION:<value>`.
- Saves `{hubUrl, setupToken, jurisdiction, deployedAt}` to `~/.config/carom-hub/<name>.json` (`0600`).
  The token lives there only while the Hub is unclaimed: whenever a command sees `setup_done: true`
  (`deploy`, `update`, `qr`, `info`) it deletes `setupToken` and keeps the rest. `update` and `deploy` without a
  token deploy without a secrets file, so wrangler keeps the secret already on the Worker.
- `reset` always rotates the token. It saves a new token, deploys it, polls `/v1/info` until `setup_done` is
  `false` (the Hub erases itself on its first request under the new secret, which signs out every device), then
  prints the setup QR. It needs your Cloudflare login. If a step fails, run `carom-hub reset` again.
- The setup QR encodes `carom://setup?v=1&h=<hub url>&t=<token>`. Show it only on your own screen.
- The jurisdiction cannot be changed after the first deploy without a reset.

## Requirements

A Cloudflare account with a registered workers.dev subdomain (Workers & Pages -> Account details -> Subdomain).
Durable Objects with SQLite are available on the free plan.

## Tests

```
node --test hub/bin/test/
```
