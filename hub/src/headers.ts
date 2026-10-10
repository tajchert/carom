// Headers the Worker sets for the Hub after verifying a token. Clients can never set them:
// the Worker strips every x-carom-* header from incoming requests first.

export const DEVICE_HEADER = "x-carom-device";
export const TOKEN_HASH_HEADER = "x-carom-token-hash";
export const INTERNAL_PREFIX = "x-carom-";
