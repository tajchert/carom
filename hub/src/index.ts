// The Worker in front of the Hub. It matches routes, enforces body limits, and verifies the
// token MAC before anything reaches the Durable Object (SPEC §6).

import { hubStub, jurisdictionOf, setupTokenOf } from "./config";
import { HttpError, errorResponse } from "./errors";
import { DEVICE_HEADER, INTERNAL_PREFIX, TOKEN_HASH_HEADER } from "./headers";
import { matchRoute, type Route } from "./routes";
import { tokenHash, verifyTokenMac } from "./token";

export { Hub } from "./hub";

/** Recomputed by the runtime for the forwarded request. */
const DROPPED_HEADERS = new Set(["authorization", "content-length", "transfer-encoding"]);

function bearerToken(request: Request): string {
  const header = request.headers.get("Authorization") ?? "";
  const match = /^Bearer ([!-~]{1,512})$/.exec(header);
  if (!match) throw new HttpError("unauthorized", "missing bearer token");
  return match[1]!;
}

/** Reads at most `limit` bytes, rejecting larger bodies without buffering them. */
async function readBody(request: Request, limit: number): Promise<ArrayBuffer | null> {
  const declared = Number(request.headers.get("Content-Length") ?? "0");
  if (declared > limit) throw new HttpError("too_large", `body exceeds ${limit} bytes`);
  if (!request.body) return null;
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > limit) {
      await reader.cancel();
      throw new HttpError("too_large", `body exceeds ${limit} bytes`);
    }
    chunks.push(value);
  }
  const body = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return body.buffer;
}

async function forwardHeaders(request: Request, route: Route, env: Env): Promise<Headers> {
  const headers = new Headers();
  for (const [name, value] of request.headers) {
    if (!name.startsWith(INTERNAL_PREFIX) && !DROPPED_HEADERS.has(name)) headers.set(name, value);
  }
  if (route.auth !== "none") {
    const token = bearerToken(request);
    const deviceId = await verifyTokenMac(setupTokenOf(env), token);
    if (!deviceId) throw new HttpError("unauthorized", "invalid token");
    headers.set(DEVICE_HEADER, deviceId);
    headers.set(TOKEN_HASH_HEADER, await tokenHash(token));
  }
  return headers;
}

async function handle(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const { route } = matchRoute(request.method, url.pathname);
  jurisdictionOf(env);
  // Every route except /v1/info depends on SETUP_TOKEN; fail closed without a usable one.
  if (route.name !== "info") setupTokenOf(env);

  const headers = await forwardHeaders(request, route, env);
  const stub = hubStub(env);
  if (route.name === "ws") {
    // WebSocket upgrades must go through fetch() with the original request.
    return stub.fetch(new Request(request, { headers }));
  }
  const body = route.maxBody > 0 ? await readBody(request, route.maxBody) : null;
  return stub.fetch(new Request(url, { method: request.method, headers, body }));
}

export default {
  async fetch(request, env): Promise<Response> {
    try {
      return await handle(request, env);
    } catch (err) {
      if (err instanceof HttpError) return err.toResponse();
      console.error(JSON.stringify({ event: "worker_error", error: err instanceof Error ? err.message : "unknown" }));
      return errorResponse("internal", "internal error");
    }
  },
} satisfies ExportedHandler<Env>;
