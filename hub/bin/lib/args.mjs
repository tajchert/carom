import { parseArgs } from 'node:util';
import { CliError } from './errors.mjs';

export const DEFAULT_NAME = 'carom-hub';
export const COMMANDS = ['deploy', 'update', 'info', 'qr', 'reset', 'help'];

const OPTIONS = {
  name: { type: 'string' },
  jurisdiction: { type: 'string' },
  url: { type: 'string' },
  yes: { type: 'boolean', short: 'y' },
  'new-token': { type: 'boolean' },
  help: { type: 'boolean', short: 'h' },
  version: { type: 'boolean', short: 'v' },
};

// Worker names are DNS labels; this also keeps the name safe to use as a file name.
const NAME_PATTERN = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;
const JURISDICTION_PATTERN = /^[a-z][a-z0-9-]*$/;

/** Parses argv (without node and script) into { command, options }. */
export function parseCli(argv) {
  let parsed;
  try {
    parsed = parseArgs({ args: argv, options: OPTIONS, allowPositionals: true });
  } catch (error) {
    throw new CliError(`${error.message}\nRun "carom-hub help" for usage.`, 2);
  }
  const { values, positionals } = parsed;

  let command = positionals[0];
  if (values.version) command = 'version';
  else if (values.help || command === undefined) command = 'help';
  if (command !== 'version' && !COMMANDS.includes(command)) {
    throw new CliError(`Unknown command "${command}". Run "carom-hub help" for usage.`, 2);
  }
  if (positionals.length > 1) {
    throw new CliError(`Unexpected argument "${positionals[1]}".`, 2);
  }

  const name = values.name ?? DEFAULT_NAME;
  if (!NAME_PATTERN.test(name)) {
    throw new CliError(`Invalid --name "${name}": use lowercase letters, digits and dashes (max 63).`, 2);
  }
  const jurisdiction = values.jurisdiction ?? null;
  if (jurisdiction !== null && !JURISDICTION_PATTERN.test(jurisdiction)) {
    throw new CliError(`Invalid --jurisdiction "${jurisdiction}" (for example: eu).`, 2);
  }

  return {
    command,
    options: {
      name,
      jurisdiction,
      url: values.url ?? null,
      yes: values.yes ?? false,
      newToken: values['new-token'] ?? false,
    },
  };
}

export const USAGE = `carom-hub - deploy and manage your Carom Hub on your own Cloudflare account

Usage: carom-hub <command> [options]

Commands:
  deploy   Deploy the Hub and print the setup QR code
  update   Redeploy the latest Hub code, keeping the same token and settings
  info     Show the Hub's /v1/info
  qr       Show the setup QR code again (only while the Hub is unclaimed)
  reset    Erase all Hub data so it can be set up again

Options:
  --name <name>          Worker name (default: ${DEFAULT_NAME})
  --jurisdiction <j>     deploy: Durable Object jurisdiction, e.g. eu (cannot be changed later)
  --url <url>            info: query this Hub URL instead of a deployed one
  --new-token            reset: also rotate the setup token (signs out every device);
                         deploy: allow replacing the token of an existing Hub you have no saved state for
  -y, --yes              Skip confirmation prompts
  -h, --help             Show this help
  -v, --version          Show the version
`;
