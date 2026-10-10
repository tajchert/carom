// Sealed device record (SPEC §5.1). The Hub stores it opaquely after a shape and signature check.

import { verifyEd25519 } from "./crypto";
import { u64, utf8 } from "./encoding";
import { badRequest, HttpError } from "./errors";
import { DEVICE_ID } from "./ids";
import { sealAad, sealSigInput } from "./seal";
import { expectB64, expectInt, expectKeys, expectObject, expectString } from "./validate";

export const RECORD_TAG = "carom/v1/rec";
const MAX_RECORD_CT_BYTES = 32 * 1024;

export interface SealedRecord {
  id: string;
  updated: number;
  kv: number;
  salt: string;
  ct: string;
  sig: string;
}

/** Checks shape, that the record belongs to `caller`, and its signature under `signPub`. */
export async function parseRecord(raw: unknown, caller: string, signPub: Uint8Array): Promise<SealedRecord> {
  const obj = expectObject(raw, "record");
  expectKeys(obj, "record", ["id", "updated", "kv", "salt", "ct", "sig"]);
  const id = expectString(obj.id, "record.id", DEVICE_ID);
  if (id !== caller) throw new HttpError("forbidden", "record.id must be the calling device");
  const updated = expectInt(obj.updated, "record.updated", 0, Number.MAX_SAFE_INTEGER);
  const kv = expectInt(obj.kv, "record.kv", 1, 0xffff_ffff);
  const salt = expectB64(obj.salt, "record.salt", 32);
  const ct = expectB64(obj.ct, "record.ct", [16, MAX_RECORD_CT_BYTES]);
  const sig = expectB64(obj.sig, "record.sig", 64);
  const input = sealSigInput(sealAad(RECORD_TAG, [utf8(id), u64(updated)], kv, salt), ct);
  if (!(await verifyEd25519(signPub, sig, input))) throw badRequest("record.sig does not verify");
  return { id, updated, kv, salt: obj.salt as string, ct: obj.ct as string, sig: obj.sig as string };
}
