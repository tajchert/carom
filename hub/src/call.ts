// What a route handler inside the Hub receives.

import { HttpError } from "./errors";
import { getDevice, type DeviceRow } from "./store/devices";

export interface HubRuntime {
  readonly sql: SqlStorage;
  readonly ctx: DurableObjectState;
  readonly env: Env;
  /** Makes sure the single alarm fires no later than the earliest pending expiry. */
  scheduleAlarm(): Promise<void>;
  /** Erases all state except the schema (a changed SETUP_TOKEN, hub.ts). */
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

/**
 * Re-reads the caller right before a write. Every await after authentication lets other
 * requests run, so the device may have been revoked (or the Hub reset) in the meantime. Call it
 * with no await between it and the write, and use the returned row, which is current.
 */
export function stillActiveCaller(call: Call): DeviceRow {
  const caller = callerOf(call);
  const current = getDevice(call.hub.sql, caller.id);
  if (!current || current.status !== "active" || current.token_hash !== caller.token_hash) {
    throw new HttpError("unauthorized", "device was revoked");
  }
  return current;
}

export function ok(body: Record<string, unknown> = {}): Response {
  return Response.json(body);
}
