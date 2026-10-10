# carom-hub CLI

Deploys your own Carom Hub to your Cloudflare account. No config files, no build step (Node >= 22).

```
npx carom-hub deploy                  # log in if needed, deploy, print the setup QR
npx carom-hub deploy --jurisdiction eu
npx carom-hub update                  # redeploy new code, same token and settings
npx carom-hub info                    # show /v1/info
npx carom-hub qr                      # reprint the setup QR (only while unclaimed)
npx carom-hub reset [--new-token]     # erase all Hub data (asks you to type the hub name)
```

Options: `--name <name>` (default `carom-hub`), `--jurisdiction <j>`, `--url <url>` (info), `--new-token`, `-y/--yes`.

## How it works

- Runs the package-local `wrangler` from the package root (where `wrangler.jsonc` lives).
- Generates `SETUP_TOKEN` (32 random bytes, base64url) and passes it via a temporary `0600` secrets file
  (`wrangler deploy --secrets-file`), deleted right after the deploy. The jurisdiction goes in as
  `--var HUB_JURISDICTION:<value>`.
- Saves `{hubUrl, setupToken, jurisdiction, deployedAt}` to `~/.config/carom-hub/<name>.json` (`0600`).
  Keep this file: it holds the setup token needed for `update` and `reset`.
- The setup QR encodes `carom://setup?v=1&h=<hub url>&t=<token>`. Show it only on your own screen.
- The jurisdiction cannot be changed after the first deploy without a reset.

## Requirements

A Cloudflare account with a registered workers.dev subdomain (Workers & Pages -> Account details -> Subdomain).
Durable Objects with SQLite are available on the free plan.

## Tests

```
node --test hub/bin/test/
```
