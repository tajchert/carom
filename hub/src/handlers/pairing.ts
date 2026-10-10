// Pairing (SPEC §6, §8): offer -> accept (pending device) -> welcome (activate) or reject.

import { callerOf, ok, stillActiveCaller, type Call, type Handler } from "../call";
import { setupTokenOf } from "../config";
import { HttpError } from "../errors";
import { deviceIdFor, newPairId, PAIR_ID } from "../ids";
import { broadcastDir, sendFrame } from "../sockets";
import { activateDevice, deletePendingDevice, getDevice, insertDevice } from "../store/devices";
import {
  MAX_OPEN_OFFERS,
  PAIRING_TTL_MS,
  countOpenOffers,
  deletePairing,
  getPairing,
  insertOffer,
  markAccepted,
  markDone,
  type PairingRow,
} from "../store/pairings";
import { mintToken, tokenHash } from "../token";
import { expectB64, expectKeys, expectObject, expectString, readJson } from "../validate";

const MAX_BOX_CT_BYTES = 16 * 1024;

export interface Candidate {
  device_id: string;
  sign_pub: string;
  kx_pub: string;
  mac: string;
  pop: string;
}

function candidateOf(sql: SqlStorage, p: PairingRow): Candidate | undefined {
  if (!p.candidate || !p.mac || !p.pop) return undefined;
  const device = getDevice(sql, p.candidate);
  if (!device) return undefined;
  return { device_id: device.id, sign_pub: device.sign_pub, kx_pub: device.kx_pub, mac: p.mac, pop: p.pop };
}

/** A live pairing offered by the caller. Other devices' pairings look like they do not exist. */
function ownPairing(call: Call): PairingRow {
  const p = getPairing(call.hub.sql, call.params.pair_id!);
  if (!p || p.offerer !== callerOf(call).id) throw new HttpError("not_found", "no such pairing");
  if (p.expires_at <= call.now) throw new HttpError("gone", "pairing expired");
  return p;
}

export const offer: Handler = async (call) => {
  const { hub, now } = call;
  if (countOpenOffers(hub.sql, now) >= MAX_OPEN_OFFERS) {
    throw new HttpError("rate_limited", `at most ${MAX_OPEN_OFFERS} pairings may be open at a time`);
  }
  const pairId = newPairId();
  const expiresAt = now + PAIRING_TTL_MS;
  insertOffer(hub.sql, pairId, callerOf(call).id, expiresAt);
  await hub.scheduleAlarm();
  return ok({ pair_id: pairId, expires_at: expiresAt });
};

function acceptablePairing(sql: SqlStorage, pairId: string, now: number): PairingRow {
  const p = getPairing(sql, pairId);
  if (!p) throw new HttpError("not_found", "no such pairing");
  if (p.expires_at <= now) throw new HttpError("gone", "pairing expired");
  if (p.state !== "waiting") throw new HttpError("conflict", "pairing was already used");
  return p;
}

export const accept: Handler = async ({ hub, request, now }) => {
  const body = expectObject(await readJson(request), "body");
  expectKeys(body, "body", ["pair_id", "sign_pub", "kx_pub", "mac", "pop"]);
  const pairId = expectString(body.pair_id, "pair_id", PAIR_ID);
  const signPub = expectB64(body.sign_pub, "sign_pub", 32);
  expectB64(body.kx_pub, "kx_pub", 32);
  expectB64(body.mac, "mac", 32);
  expectB64(body.pop, "pop", 64);
  acceptablePairing(hub.sql, pairId, now);

  const deviceId = await deviceIdFor(signPub);
  const token = await mintToken(setupTokenOf(hub.env), deviceId);
  const hash = await tokenHash(token);
  // Re-check after the awaits: the first accept consumes the pairing.
  const p = acceptablePairing(hub.sql, pairId, now);
  if (getDevice(hub.sql, deviceId)) throw new HttpError("conflict", "device already exists");

  const candidate: Candidate = {
    device_id: deviceId,
    sign_pub: body.sign_pub as string,
    kx_pub: body.kx_pub as string,
    mac: body.mac as string,
    pop: body.pop as string,
  };
  insertDevice(hub.sql, { id: deviceId, status: "pending", signPub: candidate.sign_pub, kxPub: candidate.kx_pub, tokenHash: hash, now });
  markAccepted(hub.sql, pairId, deviceId, candidate.mac, candidate.pop);
  sendFrame(hub.ctx, p.offerer, { t: "pair", pair_id: pairId, candidate });
  return ok({ device_id: deviceId, token });
};

export const status: Handler = (call) => {
  const p = ownPairing(call);
  const candidate = candidateOf(call.hub.sql, p);
  return ok({ status: p.state, ...(candidate ? { candidate } : {}) });
};

export const welcome: Handler = async (call) => {
  const { hub, request, now } = call;
  const body = expectObject(await readJson(request), "body");
  expectKeys(body, "body", ["box"]);
  const box = expectObject(body.box, "box");
  expectKeys(box, "box", ["epk", "ct"]);
  expectB64(box.epk, "box.epk", 32);
  expectB64(box.ct, "box.ct", [16, MAX_BOX_CT_BYTES]);

  stillActiveCaller(call);
  const p = ownPairing(call);
  if (p.state !== "accepted") throw new HttpError("conflict", `pairing is ${p.state}, not accepted`);
  const candidate = p.candidate ? getDevice(hub.sql, p.candidate) : null;
  if (!candidate || candidate.status !== "pending") throw new HttpError("conflict", "candidate is gone");

  activateDevice(hub.sql, candidate.id);
  // Keep the box around long enough for the candidate's next poll.
  markDone(hub.sql, p.pair_id, JSON.stringify({ epk: box.epk, ct: box.ct }), now + PAIRING_TTL_MS);
  await hub.scheduleAlarm();
  broadcastDir(hub.ctx);
  return ok();
};

export const welcomeFetch: Handler = (call) => {
  const p = getPairing(call.hub.sql, call.params.pair_id!);
  if (!p || p.candidate !== callerOf(call).id || p.expires_at <= call.now) throw new HttpError("not_found", "no such pairing");
  if (p.state !== "done" || p.box === null) throw new HttpError("not_found", "welcome not ready");
  return ok({ box: JSON.parse(p.box) as unknown });
};

export const reject: Handler = (call) => {
  const p = ownPairing(call);
  if (p.state === "done") throw new HttpError("conflict", "pairing is already done");
  if (p.candidate) deletePendingDevice(call.hub.sql, p.candidate);
  deletePairing(call.hub.sql, p.pair_id);
  return ok();
};
