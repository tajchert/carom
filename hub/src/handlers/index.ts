import type { Handler } from "../call";
import type { RouteName } from "../routes";
import * as admin from "./admin";
import * as blobs from "./blobs";
import * as devices from "./devices";
import * as keyring from "./keyring";
import * as messages from "./messages";
import * as pairing from "./pairing";

export const HANDLERS: Record<RouteName, Handler> = {
  info: admin.info,
  setup: admin.setup,
  "keyring.put": keyring.put,
  "keyring.get": keyring.get,
  "pair.offer": pairing.offer,
  "pair.accept": pairing.accept,
  "pair.status": pairing.status,
  "pair.welcome.post": pairing.welcome,
  "pair.welcome.get": pairing.welcomeFetch,
  "pair.reject": pairing.reject,
  "devices.list": devices.list,
  "devices.record": devices.putRecord,
  "devices.delete": devices.revoke,
  "messages.post": messages.post,
  "messages.get": messages.fetchMessages,
  acks: messages.acks,
  ws: messages.websocket,
  "blobs.put": blobs.put,
  "blobs.head": blobs.head,
  "blobs.get": blobs.get,
};
