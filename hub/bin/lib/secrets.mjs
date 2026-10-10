import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

/** Runs fn(path) with a 0600 JSON secrets file that is always deleted afterwards. */
export async function withSecretsFile(secrets, fn) {
  const dir = await mkdtemp(join(tmpdir(), 'carom-hub-'));
  try {
    const file = join(dir, 'secrets.json');
    await writeFile(file, JSON.stringify(secrets), { mode: 0o600 });
    return await fn(file);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}
