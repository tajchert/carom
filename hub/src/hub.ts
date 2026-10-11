// The Hub Durable Object: one SQLite-backed instance per deployment, reached only through the
// Worker, which has already verified the token MAC and set the x-carom-* headers.

import { DurableObject } from "cloudflare:workers";
import type { Call, HubRuntime } from "./call";
import { setupTokenOf } from "./config";
import { constantTimeEqual } from "./crypto";
import { utf8 } from "./encoding";
import { HttpError, errorResponse } from "./errors";
import { HANDLERS } from "./handlers";
import { applyAck } from "./handlers/messages";
import { DEVICE_HEADER, TOKEN_HASH_HEADER } from "./headers";
import { matchRoute, type AuthKind } from "./routes";
import { DATA_TABLES, migrate } from "./schema";
import { closeRevoked, deviceOf } from "./sockets";
import { earliestBlobExpiry, purgeExpiredBlobs } from "./store/blobs";
import { getDevice, touchLastSeen, type DeviceRow } from "./store/devices";
import { earliestMessageExpiry, purgeExpired } from "./store/messages";
import { SETUP_FINGERPRINT_KEY, deleteMetaExceptSchema, getMeta, isClaimed, setMeta } from "./store/meta";
import { earliestPairingExpiry, expirePairings } from "./store/pairings";
import { setupFingerprint } from "./token";
import { isObject } from "./validate";

/** Sets the single alarm to the earliest pending expiry, only when work exists. */
async function scheduleAlarm(storage: DurableObjectStorage): Promise<void> {
  const sql = storage.sql;
  const expiries = [earliestMessageExpiry(sql), earliestPairingExpiry(sql), earliestBlobExpiry(sql)].filter((t) => t !== null);
  if (expiries.length === 0) return;
  const next = Math.min(...expiries);
  const current = await storage.getAlarm();
  if (current === null || next < current) await storage.setAlarm(next);
}

async function wipe(ctx: DurableObjectState): Promise<void> {
  for (const ws of ctx.getWebSockets()) closeRevoked(ws);
  ctx.storage.transactionSync(() => {
    for (const table of DATA_TABLES) ctx.storage.sql.exec(`DELETE FROM ${table}`);
    deleteMetaExceptSchema(ctx.storage.sql);
  });
  await ctx.storage.deleteAlarm();
}

export class Hub extends DurableObject<Env> {
  private readonly runtime: HubRuntime;
  /** The fingerprint of the SETUP_TOKEN last seen, so it is hashed once per token. */
  private fingerprint: { token: string; value: string } | null = null;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.runtime = {
      sql: ctx.storage.sql,
      ctx,
      env,
      scheduleAlarm: () => scheduleAlarm(ctx.storage),
      wipe: () => wipe(ctx),
    };
    // Answered by the runtime without waking the object.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
    void ctx.blockConcurrencyWhile(async () => migrate(ctx.storage));
  }

  /**
   * Resetting the Hub means changing SETUP_TOKEN. The Hub remembers a fingerprint of the token it
   * was set up under; when the secret no longer matches, every device token is invalid anyway, so
   * the Hub erases itself and serves the request as a fresh one. A Hub set up before fingerprints
   * existed gets one now. The token comes from this object's own env, never from the request.
   */
  private async reconcileSetupToken(): Promise<void> {
    let token: string;
    try {
      token = setupTokenOf(this.env);
    } catch {
      return; // Unconfigured: the Worker refuses everything but /v1/info, which needs no check.
    }
    if (this.fingerprint?.token !== token) this.fingerprint = { token, value: await setupFingerprint(token) };
    if (!isClaimed(this.runtime.sql)) return;
    const stored = getMeta(this.runtime.sql, SETUP_FINGERPRINT_KEY);
    if (stored === null) setMeta(this.runtime.sql, SETUP_FINGERPRINT_KEY, this.fingerprint.value);
    else if (stored !== this.fingerprint.value) await wipe(this.ctx);
  }

  override async fetch(request: Request): Promise<Response> {
    try {
      const url = new URL(request.url);
      const { route, params } = matchRoute(request.method, url.pathname);
      await this.reconcileSetupToken();
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
    purgeExpiredBlobs(this.runtime.sql, now);
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
    if (device?.status !== "active") return;
    const now = Date.now();
    touchLastSeen(this.runtime.sql, device, now);
    applyAck(this.runtime, device, upTo, now);
  }

  // No state to clean up: sockets carry their device id as a tag. Echo the close frame so
  // clients see a clean close; the runtime's auto-reply did not reach clients through workers.dev.
  override webSocketClose(ws: WebSocket, code: number, reason: string): void {
    try {
      ws.close(code === 1005 || code === 1006 ? 1000 : code, reason);
    } catch {
      // Already closed.
    }
  }

  override webSocketError(_ws: WebSocket, error: unknown): void {
    console.warn(JSON.stringify({ event: "ws_error", error: error instanceof Error ? error.name : "unknown" }));
  }
}
