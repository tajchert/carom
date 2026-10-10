import { chmod, mkdir, readFile, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';

export function stateDir() {
  return join(homedir(), '.config', 'carom-hub');
}

export function statePath(name) {
  return join(stateDir(), `${name}.json`);
}

/** Returns the saved state for a hub name, or null when there is none. */
export async function readState(name) {
  let raw;
  try {
    raw = await readFile(statePath(name), 'utf8');
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
  return JSON.parse(raw);
}

/** Saves state with owner-only permissions (dir 0700, file 0600). */
export async function writeState(name, state) {
  const dir = stateDir();
  await mkdir(dir, { recursive: true, mode: 0o700 });
  await chmod(dir, 0o700);
  const file = statePath(name);
  await writeFile(file, `${JSON.stringify(state, null, 2)}\n`, { mode: 0o600 });
  await chmod(file, 0o600); // writeFile's mode is ignored when the file already exists
}
