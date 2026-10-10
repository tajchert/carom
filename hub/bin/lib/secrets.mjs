import { rmSync } from 'node:fs';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const SIGNALS = { SIGINT: 130, SIGTERM: 143, SIGHUP: 129 };

/**
 * Runs fn(path) with a 0600 JSON secrets file that is always deleted afterwards,
 * including on SIGINT/SIGTERM/SIGHUP and on process exit.
 */
export async function withSecretsFile(secrets, fn) {
  const dir = await mkdtemp(join(tmpdir(), 'carom-hub-'));
  const cleanup = () => rmSync(dir, { recursive: true, force: true });
  const handlers = Object.entries(SIGNALS).map(([signal, status]) => {
    const handler = () => {
      cleanup();
      process.exit(status);
    };
    process.once(signal, handler);
    return [signal, handler];
  });
  process.once('exit', cleanup);
  try {
    const file = join(dir, 'secrets.json');
    await writeFile(file, JSON.stringify(secrets), { mode: 0o600 });
    return await fn(file);
  } finally {
    for (const [signal, handler] of handlers) process.removeListener(signal, handler);
    process.removeListener('exit', cleanup);
    await rm(dir, { recursive: true, force: true });
  }
}
