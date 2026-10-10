// The Hub Durable Object: one SQLite-backed instance per deployment, reached only through the
// Worker, which has already verified the token MAC and set the x-carom-* headers.

import { DurableObject } from "cloudflare:workers";
import type { Call, HubRuntime } from "./call";
import { constantTimeEqual } from "./crypto";
import { utf8 } from "./encoding";
import { HttpError, errorResponse } from "./errors";
import { HANDLERS } from "./handlers";
import { applyAck } from "./handlers/messages";
import { DEVICE_HEADER, TOKEN_HASH_HEADER } from "./headers";
import { PushSender } from "./push";
import { matchRoute, type AuthKind } from "./routes";
import { DATA_TABLES, migrate } from "./schema";
import { closeRevoked, deviceOf } from "./sockets";
import { getDevice, touchLastSeen, type DeviceRow } from "./store/devices";
import { earliestMessageExpiry, purgeExpired } from "./store/messages";
import { deleteMetaExceptSchema } from "./store/meta";
import { earliestPairingExpiry, expirePairings } from "./store/pairings";
import { isObject } from "./validate";

/** Sets the single alarm to the earliest pending expiry, only when work exists. */
async function scheduleAlarm(storage: DurableObjectStorage): Promise<void> {
  const expiries = [earliestMessageExpiry(storage.sql), earliestPairingExpiry(storage.sql)].filter((t) => t !== null);
  if (expiries.length === 0) return;
  const next = Math.min(...expiries);
  const current = await storage.getAlarm();
  if (current === null || next < current) await storage.setAlarm(next);
}

async function wipe(ctx: DurableObjectState, push: PushSender): Promise<void> {
  for (const ws of ctx.getWebSockets()) closeRevoked(ws);
  ctx.storage.transactionSync(() => {
    for (const table of DATA_TABLES) ctx.storage.sql.exec(`DELETE FROM ${table}`);
    deleteMetaExceptSchema(ctx.storage.sql);
  });
  push.clear();
  await ctx.storage.deleteAlarm();
}

export class Hub extends DurableObject<Env> {
  private readonly runtime: HubRuntime;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    const push = new PushSender(ctx.storage.sql, ctx);
    this.runtime = {
      sql: ctx.storage.sql,
      ctx,
      env,
      push,
      scheduleAlarm: () => scheduleAlarm(ctx.storage),
      wipe: () => wipe(ctx, push),
    };
    // Answered by the runtime without waking the object.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
    void ctx.blockConcurrencyWhile(async () => migrate(ctx.storage));
  }

  override async fetch(request: Request): Promise<Response> {
    try {
      const url = new URL(request.url);
      const { route, params } = matchRoute(request.method, url.pathname);
      const now = Date.now();
      const caller = route.auth === "none" ? null : this.authenticate(request, route.auth, now);
      const call: Call = { hub: this.runtime, request, url, params, caller, now };
      return await HANDLERS[route.name](call);
    } catch (err) {
      if (err instanceof HttpError) return err.toResponse();
      console.error(JSON.stringify({ event: "hub_error", error: err instanceof Error ? err.message : "unknown" }));
      return errorResponse("internal", "internal error");
    }
  }

  private authenticate(request: Request, auth: AuthKind, now: number): DeviceRow {
    const sql = this.runtime.sql;
    const id = request.headers.get(DEVICE_HEADER);
    const hash = request.headers.get(TOKEN_HASH_HEADER);
    const device = id ? getDevice(sql, id) : null;
    if (!device || !hash || !constantTimeEqual(utf8(device.token_hash), utf8(hash))) {
      throw new HttpError("unauthorized", "unknown or revoked token");
    }
    if (auth === "device" && device.status !== "active") {
      throw new HttpError("forbidden", "a pending device may only fetch its welcome");
    }
    touchLastSeen(sql, device, now);
    return device;
  }

  override async alarm(): Promise<void> {
    const now = Date.now();
    purgeExpired(this.runtime.sql, now);
    expirePairings(this.runtime.sql, now);
    await scheduleAlarm(this.ctx.storage);
  }

  override webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): void {
    if (typeof message !== "string") return;
    let frame: unknown;
    try {
      frame = JSON.parse(message);
    } catch {
      return;
    }
    if (!isObject(frame) || frame.t !== "ack") return;
    const upTo = frame.up_to;
    if (typeof upTo !== "number" || !Number.isSafeInteger(upTo) || upTo < 0) return;
    const id = deviceOf(this.ctx, ws);
    const device = id ? getDevice(this.runtime.sql, id) : null;
    if (device?.status === "active") applyAck(this.runtime.sql, device, upTo);
  }

  // Nothing to clean up: sockets carry their device id as a tag and no connection state lives
  // in memory. With web_socket_auto_reply_to_close the runtime completes the close handshake.
  override webSocketClose(): void {}

  override webSocketError(_ws: WebSocket, error: unknown): void {
    console.warn(JSON.stringify({ event: "ws_error", error: error instanceof Error ? error.name : "unknown" }));
  }
}
