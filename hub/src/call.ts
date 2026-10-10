// What a route handler inside the Hub receives.

import { HttpError } from "./errors";
import type { PushSender } from "./push";
import type { DeviceRow } from "./store/devices";

export interface HubRuntime {
  readonly sql: SqlStorage;
  readonly ctx: DurableObjectState;
  readonly env: Env;
  readonly push: PushSender;
  /** Makes sure the single alarm fires no later than the earliest pending expiry. */
  scheduleAlarm(): Promise<void>;
  /** Erases all state except the schema (POST /v1/reset). */
  wipe(): Promise<void>;
}

export interface Call {
  hub: HubRuntime;
  request: Request;
  url: URL;
  params: Record<string, string>;
  /** The authenticated device; null on unauthenticated routes. */
  caller: DeviceRow | null;
  now: number;
}

export type Handler = (call: Call) => Response | Promise<Response>;

export function callerOf(call: Call): DeviceRow {
  if (!call.caller) throw new HttpError("unauthorized", "authentication required");
  return call.caller;
}

export function ok(body: Record<string, unknown> = {}): Response {
  return Response.json(body);
}
