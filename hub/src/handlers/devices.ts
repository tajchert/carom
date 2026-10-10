// Directory (SPEC §5, §6): list, publish record, push subscription, revoke.

import { callerOf, ok, stillActiveCaller, type Handler } from "../call";
import { b64decode } from "../encoding";
import { HttpError, badRequest } from "../errors";
import { DEVICE_ID } from "../ids";
import { parseRecord } from "../record";
import { broadcastDir, revokeSockets } from "../sockets";
import {
  clearPushSubscription,
  deleteDevice,
  getDevice,
  listActiveDevices,
  setPushSubscription,
  setRecord,
} from "../store/devices";
import { dropRecipient } from "../store/messages";
import { deletePairingsOf } from "../store/pairings";
import { decodeSubscriptionKeys } from "../webpush";
import { expectKeys, expectObject, expectString, readJson } from "../validate";

const MAX_ENDPOINT_LENGTH = 2048;

export const list: Handler = ({ hub }) => {
  const devices = listActiveDevices(hub.sql).map((d) => ({
    id: d.id,
    sign_pub: d.sign_pub,
    kx_pub: d.kx_pub,
    created_at: d.created_at,
    last_seen: d.last_seen,
    has_push: d.push_endpoint !== null,
    ...(d.record === null ? {} : { record: JSON.parse(d.record) as unknown }),
  }));
  return ok({ devices });
};

export const putRecord: Handler = async (call) => {
  const caller = callerOf(call);
  const body = expectObject(await readJson(call.request), "body");
  expectKeys(body, "body", ["record"]);
  const record = await parseRecord(body.record, caller.id, b64decode(caller.sign_pub)!);
  stillActiveCaller(call);
  setRecord(call.hub.sql, caller.id, JSON.stringify(record));
  broadcastDir(call.hub.ctx, caller.id);
  return ok();
};

/** Hostnames compare case-insensitively, ignoring trailing dots. */
function normalizeHost(hostname: string): string {
  return hostname.toLowerCase().replace(/\.+$/, "");
}

/** The URL parser has already turned every IPv4 form (hex, short, trailing dot) into dotted decimal. */
const IPV4 = /^\d+\.\d+\.\d+\.\d+$/;

/** SPEC §6: https, the default port, and a DNS hostname that is not an IP, localhost or the Hub. */
function checkPushEndpoint(endpoint: string, hubUrl: URL): void {
  let url: URL;
  try {
    url = new URL(endpoint);
  } catch {
    throw badRequest("endpoint is not a URL");
  }
  if (endpoint.length > MAX_ENDPOINT_LENGTH || url.protocol !== "https:") throw badRequest("endpoint must be an https: URL");
  if (url.port !== "") throw badRequest("endpoint must use the default port");
  const host = normalizeHost(url.hostname);
  if (host.startsWith("[") || IPV4.test(host)) throw badRequest("endpoint must have a DNS hostname, not an IP address");
  if (host === "localhost" || host.endsWith(".localhost")) throw badRequest("endpoint must not point at localhost");
  if (host === normalizeHost(hubUrl.hostname)) throw badRequest("endpoint must not point at the Hub");
}

export const putPush: Handler = async (call) => {
  const body = expectObject(await readJson(call.request), "body");
  expectKeys(body, "body", ["endpoint", "p256dh", "auth"]);
  const endpoint = expectString(body.endpoint, "endpoint");
  checkPushEndpoint(endpoint, call.url);
  if (!decodeSubscriptionKeys(expectString(body.p256dh, "p256dh"), expectString(body.auth, "auth"))) {
    throw badRequest("p256dh or auth is invalid");
  }
  const caller = stillActiveCaller(call);
  setPushSubscription(call.hub.sql, caller.id, endpoint, body.p256dh as string, body.auth as string);
  return ok();
};

export const deletePush: Handler = (call) => {
  clearPushSubscription(call.hub.sql, callerOf(call).id);
  return ok();
};

export const revoke: Handler = (call) => {
  const { hub } = call;
  const param = call.params.id!;
  const id = param === "self" ? callerOf(call).id : param;
  const device = DEVICE_ID.test(id) ? getDevice(hub.sql, id) : null;
  if (!device) throw new HttpError("not_found", "no such device");

  deleteDevice(hub.sql, id);
  dropRecipient(hub.sql, id, device.cursor);
  deletePairingsOf(hub.sql, id);
  revokeSockets(hub.ctx, id);
  broadcastDir(hub.ctx, id);
  return ok();
};
