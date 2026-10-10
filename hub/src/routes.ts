// The route table from SPEC §6, shared by the Worker (auth + body limits) and the Hub (dispatch).

import { HttpError } from "./errors";

/**
 * - none: no token.
 * - device: an active device token.
 * - candidate: an active or pending token (only GET /v1/pair/:pair_id/welcome; the candidate
 *   is activated by the offerer before it fetches the welcome box).
 */
export type AuthKind = "none" | "device" | "candidate";

export type RouteName =
  | "info"
  | "setup"
  | "reset"
  | "keyring.put"
  | "keyring.get"
  | "pair.offer"
  | "pair.accept"
  | "pair.status"
  | "pair.welcome.post"
  | "pair.welcome.get"
  | "pair.reject"
  | "devices.list"
  | "devices.record"
  | "devices.push.put"
  | "devices.push.delete"
  | "devices.delete"
  | "messages.post"
  | "messages.get"
  | "acks"
  | "ws";

export interface Route {
  name: RouteName;
  method: string;
  path: string;
  auth: AuthKind;
  /** Request body limit in bytes; 0 means the route takes no body. */
  maxBody: number;
}

const SMALL = 4 * 1024;
const MEDIUM = 64 * 1024;
/** One serialized envelope (98 304 bytes, SPEC §3) plus the {"envelope":…} wrapper. */
export const MESSAGE_BODY_LIMIT = 98_304 + 1024;

// Literal paths come before parameterized ones with the same shape.
export const ROUTES: readonly Route[] = [
  { name: "info", method: "GET", path: "/v1/info", auth: "none", maxBody: 0 },
  { name: "setup", method: "POST", path: "/v1/setup", auth: "none", maxBody: SMALL },
  { name: "reset", method: "POST", path: "/v1/reset", auth: "none", maxBody: SMALL },
  { name: "keyring.put", method: "PUT", path: "/v1/keyring", auth: "device", maxBody: MEDIUM },
  { name: "keyring.get", method: "GET", path: "/v1/keyring", auth: "device", maxBody: 0 },
  { name: "pair.offer", method: "POST", path: "/v1/pair/offer", auth: "device", maxBody: SMALL },
  { name: "pair.accept", method: "POST", path: "/v1/pair/accept", auth: "none", maxBody: SMALL },
  { name: "pair.status", method: "GET", path: "/v1/pair/:pair_id", auth: "device", maxBody: 0 },
  { name: "pair.welcome.post", method: "POST", path: "/v1/pair/:pair_id/welcome", auth: "device", maxBody: MEDIUM },
  { name: "pair.welcome.get", method: "GET", path: "/v1/pair/:pair_id/welcome", auth: "candidate", maxBody: 0 },
  { name: "pair.reject", method: "POST", path: "/v1/pair/:pair_id/reject", auth: "device", maxBody: SMALL },
  { name: "devices.list", method: "GET", path: "/v1/devices", auth: "device", maxBody: 0 },
  { name: "devices.record", method: "PUT", path: "/v1/devices/self/record", auth: "device", maxBody: MEDIUM },
  { name: "devices.push.put", method: "PUT", path: "/v1/devices/self/push", auth: "device", maxBody: SMALL },
  { name: "devices.push.delete", method: "DELETE", path: "/v1/devices/self/push", auth: "device", maxBody: 0 },
  { name: "devices.delete", method: "DELETE", path: "/v1/devices/:id", auth: "device", maxBody: 0 },
  { name: "messages.post", method: "POST", path: "/v1/messages", auth: "device", maxBody: MESSAGE_BODY_LIMIT },
  { name: "messages.get", method: "GET", path: "/v1/messages", auth: "device", maxBody: 0 },
  { name: "acks", method: "POST", path: "/v1/acks", auth: "device", maxBody: SMALL },
  { name: "ws", method: "GET", path: "/v1/ws", auth: "device", maxBody: 0 },
];

export interface RouteMatch {
  route: Route;
  params: Record<string, string>;
}

const PARAM = /^[A-Za-z0-9_-]{1,64}$/;

function matchPath(pattern: string, path: string): Record<string, string> | null {
  const want = pattern.split("/");
  const got = path.split("/");
  if (want.length !== got.length) return null;
  const params: Record<string, string> = {};
  for (let i = 0; i < want.length; i++) {
    const w = want[i]!;
    const g = got[i]!;
    if (w.startsWith(":")) {
      if (!PARAM.test(g)) return null;
      params[w.slice(1)] = g;
    } else if (w !== g) {
      return null;
    }
  }
  return params;
}

export function matchRoute(method: string, path: string): RouteMatch {
  for (const route of ROUTES) {
    if (route.method !== method) continue;
    const params = matchPath(route.path, path);
    if (params) return { route, params };
  }
  throw new HttpError("not_found", "no such route");
}
