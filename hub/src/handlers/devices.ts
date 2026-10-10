// Directory (SPEC §5, §6): list, publish record, revoke.

import { callerOf, ok, stillActiveCaller, type Handler } from "../call";
import { b64decode } from "../encoding";
import { HttpError } from "../errors";
import { DEVICE_ID } from "../ids";
import { parseRecord } from "../record";
import { broadcastDir, revokeSockets } from "../sockets";
import {
  deleteDevice,
  getDevice,
  listActiveDevices,
  setRecord,
} from "../store/devices";
import { dropRecipient } from "../store/messages";
import { deletePairingsOf } from "../store/pairings";
import { expectKeys, expectObject, readJson } from "../validate";

export const list: Handler = ({ hub }) => {
  const devices = listActiveDevices(hub.sql).map((d) => ({
    id: d.id,
    sign_pub: d.sign_pub,
    kx_pub: d.kx_pub,
    created_at: d.created_at,
    last_seen: d.last_seen,
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
