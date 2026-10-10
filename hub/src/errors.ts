// JSON error format from SPEC §6: {"error": {"code", "message"}} plus optional extra fields.

export type ErrorCode =
  | "bad_request"
  | "unauthorized"
  | "forbidden"
  | "not_found"
  | "conflict"
  | "gone"
  | "too_large"
  | "rate_limited"
  // Not in SPEC §6: the Hub cannot serve requests at all (missing or weak SETUP_TOKEN,
  // invalid HUB_JURISDICTION) or hit an unexpected failure.
  | "unavailable"
  | "internal";

const STATUS: Record<ErrorCode, number> = {
  bad_request: 400,
  unauthorized: 401,
  forbidden: 403,
  not_found: 404,
  conflict: 409,
  gone: 410,
  too_large: 413,
  rate_limited: 429,
  unavailable: 503,
  internal: 500,
};

export class HttpError extends Error {
  constructor(
    readonly code: ErrorCode,
    message: string,
    readonly extra: Record<string, unknown> = {},
  ) {
    super(message);
  }

  toResponse(): Response {
    return errorResponse(this.code, this.message, this.extra);
  }
}

export function errorResponse(code: ErrorCode, message: string, extra: Record<string, unknown> = {}): Response {
  return Response.json({ error: { code, message }, ...extra }, { status: STATUS[code] });
}

export function badRequest(message: string): HttpError {
  return new HttpError("bad_request", message);
}
