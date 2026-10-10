// Server -> client WebSocket frames (SPEC §7). Every socket is tagged with its device id, so
// nothing about connections lives in memory and the Hub can hibernate.

export type Frame =
  | { t: "msg"; seq: number; envelope: unknown }
  | { t: "dir" }
  | { t: "pair"; pair_id: string; candidate: unknown }
  | { t: "revoked" };

/** Close code sent to a revoked device (application range 4000-4999). */
export const REVOKED_CLOSE_CODE = 4001;

function safeSend(ws: WebSocket, data: string): void {
  try {
    ws.send(data);
  } catch {
    // The socket is closing; the frame is either redundant or will be fetched on reconnect.
  }
}

export function hasLiveSocket(ctx: DurableObjectState, deviceId: string): boolean {
  return ctx.getWebSockets(deviceId).length > 0;
}

export function sendFrame(ctx: DurableObjectState, deviceId: string, frame: Frame): void {
  const data = JSON.stringify(frame);
  for (const ws of ctx.getWebSockets(deviceId)) safeSend(ws, data);
}

/** Sends a stored message without re-parsing its envelope JSON. */
export function sendMessageFrame(ctx: DurableObjectState, deviceId: string, seq: number, envelopeJson: string): void {
  const data = messageFrame(seq, envelopeJson);
  for (const ws of ctx.getWebSockets(deviceId)) safeSend(ws, data);
}

export function messageFrame(seq: number, envelopeJson: string): string {
  return `{"t":"msg","seq":${seq},"envelope":${envelopeJson}}`;
}

export function broadcastDir(ctx: DurableObjectState, exceptId?: string): void {
  const data = JSON.stringify({ t: "dir" } satisfies Frame);
  for (const ws of ctx.getWebSockets()) {
    if (exceptId === undefined || deviceOf(ctx, ws) !== exceptId) safeSend(ws, data);
  }
}

/** Tells a device it was revoked, then closes its sockets. */
export function revokeSockets(ctx: DurableObjectState, deviceId: string): void {
  for (const ws of ctx.getWebSockets(deviceId)) closeRevoked(ws);
}

export function closeRevoked(ws: WebSocket): void {
  safeSend(ws, JSON.stringify({ t: "revoked" } satisfies Frame));
  try {
    ws.close(REVOKED_CLOSE_CODE, "revoked");
  } catch {
    // Already closed.
  }
}

export function deviceOf(ctx: DurableObjectState, ws: WebSocket): string | undefined {
  return ctx.getTags(ws)[0];
}
