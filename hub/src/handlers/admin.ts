// GET /v1/info, POST /v1/setup, POST /v1/reset.

import pkg from "../../package.json";
import { ok, type Handler } from "../call";
import { jurisdictionOf, setupTokenOf } from "../config";
import { HttpError, badRequest } from "../errors";
import { deviceIdFor } from "../ids";
import { insertDevice } from "../store/devices";
import { isLocked, recordFailure, type LockedRoute } from "../store/lockouts";
import { isClaimed, setMeta } from "../store/meta";
import { b64encode } from "../encoding";
import { mintToken, setupTokenMatches, tokenHash } from "../token";
import { expectB64, expectKeys, expectObject, expectString, readJson } from "../validate";

export const RESET_CONFIRMATION = "erase everything";

export const info: Handler = async ({ hub }) => {
  const jurisdiction = jurisdictionOf(hub.env);
  const vapid = await hub.push.vapid();
  return ok({
    name: "carom-hub",
    protocol: 1,
    version: pkg.version,
    setup_done: isClaimed(hub.sql),
    vapid_public_key: vapid.publicKey,
    jurisdiction: jurisdiction === "" ? null : jurisdiction,
  });
};

/** Checks the lockout and the presented setup token, counting a mismatch as a failure. */
async function checkSetupToken(sql: SqlStorage, env: Env, route: LockedRoute, presented: unknown, now: number): Promise<void> {
  if (isLocked(sql, route, now)) throw new HttpError("rate_limited", "too many failed attempts; try again later");
  const token = expectString(presented, "setup_token");
  if (!(await setupTokenMatches(setupTokenOf(env), token))) {
    recordFailure(sql, route, now);
    throw new HttpError("forbidden", "wrong setup token");
  }
}

export const setup: Handler = async ({ hub, request, now }) => {
  const body = expectObject(await readJson(request), "body");
  expectKeys(body, "body", ["setup_token", "sign_pub", "kx_pub"]);
  if (isClaimed(hub.sql)) throw new HttpError("conflict", "setup was already done");
  await checkSetupToken(hub.sql, hub.env, "setup", body.setup_token, now);
  const signPub = expectB64(body.sign_pub, "sign_pub", 32);
  const kxPub = expectB64(body.kx_pub, "kx_pub", 32);

  const deviceId = await deviceIdFor(signPub);
  const token = await mintToken(setupTokenOf(hub.env), deviceId);
  const hash = await tokenHash(token);
  // Re-check after the awaits above: a concurrent setup may have won.
  if (isClaimed(hub.sql)) throw new HttpError("conflict", "setup was already done");
  insertDevice(hub.sql, { id: deviceId, status: "active", signPub: b64encode(signPub), kxPub: b64encode(kxPub), tokenHash: hash, now });
  setMeta(hub.sql, "claimed", "1");
  return ok({ device_id: deviceId, token });
};

export const reset: Handler = async ({ hub, request, now }) => {
  const body = expectObject(await readJson(request), "body");
  expectKeys(body, "body", ["setup_token", "confirm"]);
  await checkSetupToken(hub.sql, hub.env, "reset", body.setup_token, now);
  if (body.confirm !== RESET_CONFIRMATION) throw badRequest(`confirm must be "${RESET_CONFIRMATION}"`);
  await hub.wipe();
  return ok();
};
