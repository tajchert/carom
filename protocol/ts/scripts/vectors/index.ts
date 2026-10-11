// Builds every vector file in memory: file name → JSON content.

import { b64Vectors, collapseVectors, deviceIdVectors, encodingVectors, kdfVectors, paddingVectors } from './basics.ts';
import { blobVectors } from './blob.ts';
import { envelopeReceiveVectors, envelopeSealVectors, envelopeShapeVectors } from './envelope.ts';
import type { VectorFile } from './fixtures.ts';
import { boxVectors, pairingVectors, rekeyVectors } from './keys.ts';
import { qrVectors, tokenVectors, uuidVectors } from './misc.ts';
import { recordVectors } from './record.ts';

export function buildVectors(): Record<string, VectorFile> {
  return {
    'b64.json': b64Vectors(),
    'encoding.json': encodingVectors(),
    'device_id.json': deviceIdVectors(),
    'kdf.json': kdfVectors(),
    'collapse.json': collapseVectors(),
    'padding.json': paddingVectors(),
    'envelope_seal.json': envelopeSealVectors(),
    'envelope_receive.json': envelopeReceiveVectors(),
    'envelope_shape.json': envelopeShapeVectors(),
    'record.json': recordVectors(),
    'box.json': boxVectors(),
    'pairing.json': pairingVectors(),
    'rekey.json': rekeyVectors(),
    'token.json': tokenVectors(),
    'qr.json': qrVectors(),
    'uuidv7.json': uuidVectors(),
    'blob.json': blobVectors(),
  };
}

export function serializeVectorFile(file: VectorFile): string {
  return JSON.stringify(file, null, 2) + '\n';
}
