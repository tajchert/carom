// The Worker in front of the Hub. It matches routes, enforces body and rate limits, verifies the
// token MAC and the setup token before anything reaches the Durable Object (SPEC §6).

import { hubStub, jurisdictionOf, setupTokenOf } from "./config";
import { HttpError, badRequest, errorResponse } from "./errors";
import { DEVICE_HEADER, INTERNAL_PREFIX, TOKEN_HASH_HEADER } from "./headers";
import { matchRoute, type Route } from "./routes";
import { setupTokenMatches, tokenHash, verifyTokenMac } from "./token";
import { expectObject, expectString } from "./validate";

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

/** Answers 429 once `key` has used up its budget in `limiter` (wrangler.jsonc). */
async function enforceLimit(limiter: RateLimit, key: string): Promise<void> {
  const { success } = await limiter.limit({ key });
  if (!success) throw new HttpError("rate_limited", "too many requests; try again later");
}

/** Refuses a wrong setup token here, so guesses at /v1/setup never wake the Hub. */
async function checkSetupToken(body: ArrayBuffer | null, env: Env): Promise<void> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder().decode(body ?? new ArrayBuffer(0)));
  } catch {
    throw badRequest("body must be valid JSON");
  }
  const token = expectString(expectObject(parsed, "body").setup_token, "setup_token");
  if (!(await setupTokenMatches(setupTokenOf(env), token))) throw new HttpError("forbidden", "wrong setup token");
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
    // A revoked device's token still has a valid MAC, so this also caps its retries.
    await enforceLimit(env.DEVICE_LIMITER, deviceId);
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

  if (route.auth === "none") {
    // Per route, so a burst of /v1/info polls can't use up the budget for setup or pairing.
    await enforceLimit(env.ANON_LIMITER, `${route.name}:${request.headers.get("CF-Connecting-IP") ?? "unknown"}`);
  }
  const headers = await forwardHeaders(request, route, env);
  if (route.name === "ws") {
    // WebSocket upgrades must go through fetch() with the original request.
    return hubStub(env).fetch(new Request(request, { headers }));
  }
  const body = route.maxBody > 0 ? await readBody(request, route.maxBody) : null;
  if (route.name === "setup") await checkSetupToken(body, env);
  return hubStub(env).fetch(new Request(url, { method: request.method, headers, body }));
}

/** Stamps the Hub's clock on a response (SPEC §6) so clients can detect a wrong device clock. */
function withDate(response: Response): Response {
  // A 101 carries the WebSocket and must not be rebuilt.
  if (response.status === 101) return response;
  const stamped = new Response(response.body, response);
  stamped.headers.set("Date", new Date().toUTCString());
  return stamped;
}

export default {
  async fetch(request, env): Promise<Response> {
    try {
      return withDate(await handle(request, env));
    } catch (err) {
      if (err instanceof HttpError) return withDate(err.toResponse());
      console.error(JSON.stringify({ event: "worker_error", error: err instanceof Error ? err.message : "unknown" }));
      return withDate(errorResponse("internal", "internal error"));
    }
  },
} satisfies ExportedHandler<Env>;
