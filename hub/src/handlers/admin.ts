// GET /v1/info, POST /v1/setup. There is no reset route: changing SETUP_TOKEN wipes the Hub (hub.ts).

import pkg from "../../package.json";
import { ok, type Handler } from "../call";
import { jurisdictionOf, setupTokenOf } from "../config";
import { HttpError } from "../errors";
import { deviceIdFor } from "../ids";
import { insertDevice } from "../store/devices";
import { SETUP_FINGERPRINT_KEY, isClaimed, setMeta } from "../store/meta";
import { b64encode } from "../encoding";
import { mintToken, setupFingerprint, setupTokenMatches, tokenHash } from "../token";
import { expectB64, expectKeys, expectObject, expectString, readJson } from "../validate";

export const info: Handler = ({ hub }) => {
  const jurisdiction = jurisdictionOf(hub.env);
  return ok({
    name: "carom-hub",
    protocol: 1,
    version: pkg.version,
    setup_done: isClaimed(hub.sql),
    jurisdiction: jurisdiction === "" ? null : jurisdiction,
  });
};

/**
 * The Worker already refused a wrong setup token (index.ts); this repeats the check in case the
 * Hub is ever reached another way.
 */
async function checkSetupToken(env: Env, presented: unknown): Promise<void> {
  const token = expectString(presented, "setup_token");
  if (!(await setupTokenMatches(setupTokenOf(env), token))) throw new HttpError("forbidden", "wrong setup token");
}

export const setup: Handler = async ({ hub, request, now }) => {
  const body = expectObject(await readJson(request), "body");
  expectKeys(body, "body", ["setup_token", "sign_pub", "kx_pub"]);
  if (isClaimed(hub.sql)) throw new HttpError("conflict", "setup was already done");
  await checkSetupToken(hub.env, body.setup_token);
  const signPub = expectB64(body.sign_pub, "sign_pub", 32);
  const kxPub = expectB64(body.kx_pub, "kx_pub", 32);

  const deviceId = await deviceIdFor(signPub);
  const token = await mintToken(setupTokenOf(hub.env), deviceId);
  const hash = await tokenHash(token);
  // Re-check after the awaits above: a concurrent setup may have won.
  if (isClaimed(hub.sql)) throw new HttpError("conflict", "setup was already done");
  insertDevice(hub.sql, { id: deviceId, status: "active", signPub: b64encode(signPub), kxPub: b64encode(kxPub), tokenHash: hash, now });
  setMeta(hub.sql, "claimed", "1");
  setMeta(hub.sql, SETUP_FINGERPRINT_KEY, await setupFingerprint(setupTokenOf(hub.env)));
  return ok({ device_id: deviceId, token });
};
