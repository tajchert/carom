// Domain-separation strings and limits from SPEC v1.

export const PROTOCOL_VERSION = 1;

export const INFO = {
  msg: 'carom/v1/msg',
  collapse: 'carom/v1/collapse',
  box: 'carom/v1/box',
  pair: 'carom/v1/pair',
  tokenKey: 'carom/v1/token-key',
  blobConvergent: 'carom/v1/blob-conv',
} as const;

export const TAG = {
  envelope: 'carom/v1/env',
  record: 'carom/v1/rec',
  rekey: 'carom/v1/rekey',
  welcome: 'carom/v1/welcome',
  pairRequest: 'carom/v1/pair-req',
  blob: 'carom/v1/blob',
} as const;

export const LIMITS = {
  maxPlaintext: 65_536,
  maxEnvelopeBytes: 98_304,
  minTtlS: 60,
  maxTtlS: 604_800,
  maxRecipients: 32,
  clockSkewMs: 300_000,
  maxKv: 0xffff_ffff,
  /** Largest blob ciphertext (SPEC §11.2). */
  maxBlobBytes: 1_048_576,
  /** Largest blob plaintext: its padded length plus the 16-byte tag stays within maxBlobBytes. */
  maxBlobPlaintext: 1_044_476,
} as const;

export const KEY_LENGTH = 32;
export const SALT_LENGTH = 32;
export const SIG_LENGTH = 64;
export const AEAD_TAG_LENGTH = 16;
