// Keyring (SPEC §5.5): serialized group-key versions with per-device boxes.

import { callerOf, ok, type Handler } from "../call";
import { HttpError, badRequest } from "../errors";
import { DEVICE_ID } from "../ids";
import { broadcastDir } from "../sockets";
import { insertKeyring, keyringAfter, latestKv } from "../store/keyring";
import { expectB64, expectInt, expectKeys, expectObject, expectString, parseIntParam, readJson } from "../validate";

const U32_MAX = 0xffff_ffff;
const MAX_BOXES = 64;
/** A boxed 32-byte group key plus the AEAD tag; a little headroom for future formats. */
const MAX_BOX_CT_BYTES = 256;

/**
 * kv must be the stored kv + 1. An empty keyring means only the setup key (kv 1) exists, which
 * the setup device may or may not have published, so both 1 and 2 are accepted then.
 */
function isNextKv(sql: SqlStorage, kv: number): boolean {
  const latest = latestKv(sql);
  return latest === null ? kv === 1 || kv === 2 : kv === latest + 1;
}

export const put: Handler = async (call) => {
  const { hub } = call;
  const caller = callerOf(call);
  const body = expectObject(await readJson(call.request), "body");
  expectKeys(body, "body", ["kv", "by", "boxes", "sig"]);
  const kv = expectInt(body.kv, "kv", 1, U32_MAX);
  const by = expectString(body.by, "by", DEVICE_ID);
  if (by !== caller.id) throw new HttpError("forbidden", "by must be the calling device");
  const boxes = expectObject(body.boxes, "boxes");
  const ids = Object.keys(boxes);
  if (ids.length < 1 || ids.length > MAX_BOXES) throw badRequest(`boxes must hold 1..${MAX_BOXES} entries`);
  for (const id of ids) {
    if (!DEVICE_ID.test(id)) throw badRequest("boxes keys must be device ids");
    const box = expectObject(boxes[id], `boxes.${id}`);
    expectKeys(box, `boxes.${id}`, ["epk", "ct"]);
    expectB64(box.epk, `boxes.${id}.epk`, 32);
    expectB64(box.ct, `boxes.${id}.ct`, [16, MAX_BOX_CT_BYTES]);
  }
  const sig = expectString(body.sig, "sig");
  expectB64(sig, "sig", 64);

  if (!isNextKv(hub.sql, kv)) {
    throw new HttpError("conflict", "kv must be the current kv + 1", { kv: latestKv(hub.sql) ?? 1 });
  }
  insertKeyring(hub.sql, kv, by, sig, JSON.stringify(boxes));
  broadcastDir(hub.ctx);
  return ok();
};

export const get: Handler = (call) => {
  const have = parseIntParam(call.url.searchParams.get("have"), "have", 0, 0, U32_MAX);
  const versions = keyringAfter(call.hub.sql, have).map((r) => ({
    kv: r.kv,
    by: r.by_device,
    sig: r.sig,
    boxes: JSON.parse(r.boxes) as unknown,
  }));
  return ok({ versions });
};
