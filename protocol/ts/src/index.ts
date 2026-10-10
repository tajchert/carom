// @carom/protocol: reference implementation of Carom protocol v1 (protocol/SPEC.md).

export { b64decode, b64decodeLength, b64encode, B64Error } from './b64.ts';
export { concat, equalBytes, fromHex, fromUtf8, lp, toHex, u32be, u64, utf8, type Bytes } from './bytes.ts';
export { collapseInput, collapseValue } from './collapse.ts';
export { INFO, KEY_LENGTH, LIMITS, PROTOCOL_VERSION, SALT_LENGTH, SIG_LENGTH, TAG } from './constants.ts';
export {
  deviceIdFromSignPub,
  deviceIdMatches,
  generateIdentity,
  identityFromSecrets,
  isDeviceId,
  sortIds,
  type Identity,
} from './device.ts';
export { openBox, parseBox, sealBox, type Box, type OpenBoxInput, type SealBoxInput } from './box.ts';
export {
  envelopeAad,
  envelopeHeaderFields,
  envelopeSigInput,
  joinRecipients,
  openEnvelope,
  sealEnvelope,
  verifyEnvelopeSignature,
  type EnvelopeHeader,
  type GroupKeys,
  type OpenEnvelopeError,
  type OpenEnvelopeResult,
  type SealEnvelopeInput,
} from './envelope.ts';
export { validateEnvelopeShape, type Envelope, type ShapeErrorCode, type ShapeResult } from './envelope-shape.ts';
export { boxKey, collapseKey, msgKey, pairKey, tokenKey } from './kdf.ts';
export {
  collapseRealKey,
  DEFAULT_TTL_S,
  isMessageType,
  isValidBody,
  MESSAGE_TYPES,
  type ClipboardSetBody,
  type DeviceStatusBody,
  type MessageBodies,
  type MessageType,
  type NotificationDismissBody,
  type NotificationPostedBody,
  type NotificationRemovedBody,
} from './messages.ts';
export {
  buildPairAccept,
  encodeWelcome,
  isValidWelcome,
  openWelcome,
  pairRequestInput,
  pairRequestMac,
  sealWelcome,
  signPairPop,
  verifyPairCandidate,
  verifyPairPop,
  welcomeAad,
  type CandidateError,
  type CandidateResult,
  type PairAcceptBody,
  type PairCandidate,
  type SealWelcomeInput,
  type Welcome,
  type WelcomeKey,
  type WelcomeResult,
} from './pairing.ts';
export { decodePayload, encodePayload, paddedLength, padPayload, type Payload } from './payload.ts';
export { defaultRng, type Rng } from './primitives.ts';
export {
  buildPairUri,
  buildSetupUri,
  isValidHubUrl,
  isValidPairId,
  isValidSetupToken,
  parseQrUri,
  percentEncode,
  type QrPayload,
} from './qr.ts';
export {
  checkFreshness,
  isAddressedTo,
  isReplyAllowed,
  isStaleClipboard,
  receiveEnvelope,
  trustedSenderKey,
  type ReceiveContext,
  type ReceiveError,
  type ReceiveResult,
} from './receive.ts';
export {
  encodeRecord,
  isSealedRecordShape,
  isValidDeviceRecord,
  openRecord,
  recordAad,
  sealRecord,
  type DeviceKind,
  type DeviceRecord,
  type OpenRecordContext,
  type OpenRecordError,
  type OpenRecordResult,
  type ReceiveRule,
  type SealedRecord,
  type SealRecordInput,
} from './record.ts';
export {
  buildRekey,
  isKeyringVersionShape,
  openRekey,
  rekeyBoxAad,
  rekeySigInput,
  verifyRekeySignature,
  type BuildRekeyInput,
  type KeyringVersion,
  type OpenRekeyContext,
  type OpenRekeyError,
  type OpenRekeyResult,
  type RekeyMember,
} from './rekey.ts';
export { defaultReceive, routeRecipients, type RouteInput } from './routing.ts';
export { isValidKv, sealAad, sealSigInput } from './seal.ts';
export { makeToken, parseToken, tokenHash, tokenMac, verifyToken, type ParsedToken } from './token.ts';
export {
  compareMsgIds,
  createUuidV7Generator,
  formatUuidV7,
  isUuidV7,
  uuidV7Timestamp,
  type UuidV7Options,
} from './uuidv7.ts';
