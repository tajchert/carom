// Regenerates protocol/vectors/*.json from fixed seeds. Run with `npm run vectors`.

import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildVectors, serializeVectorFile } from './vectors/index.ts';

export const VECTORS_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'vectors');

mkdirSync(VECTORS_DIR, { recursive: true });
for (const [name, file] of Object.entries(buildVectors())) {
  writeFileSync(join(VECTORS_DIR, name), serializeVectorFile(file));
  console.log(`wrote vectors/${name}`);
}
