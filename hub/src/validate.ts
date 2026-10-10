// Strict request-shape helpers. Each throws a 400 bad_request naming the offending field.

import { b64decode } from "./encoding";
import { badRequest } from "./errors";

export type JsonObject = Record<string, unknown>;

export function isObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function expectObject(value: unknown, what: string): JsonObject {
  if (!isObject(value)) throw badRequest(`${what} must be a JSON object`);
  return value;
}

/** Rejects fields outside `allowed` and requires every field in `required`. */
export function expectKeys(obj: JsonObject, what: string, required: readonly string[], optional: readonly string[] = []): void {
  for (const key of required) {
    if (!(key in obj)) throw badRequest(`${what}.${key} is required`);
  }
  for (const key of Object.keys(obj)) {
    if (!required.includes(key) && !optional.includes(key)) throw badRequest(`${what}.${key} is not allowed`);
  }
}

export function expectString(value: unknown, field: string, pattern?: RegExp): string {
  if (typeof value !== "string" || (pattern && !pattern.test(value))) throw badRequest(`${field} is invalid`);
  return value;
}

export function expectInt(value: unknown, field: string, min: number, max: number): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < min || value > max) {
    throw badRequest(`${field} must be an integer in ${min}..${max}`);
  }
  return value;
}

/** A b64 string; `length` is an exact byte count or an inclusive [min, max] range. */
export function expectB64(value: unknown, field: string, length: number | [number, number]): Uint8Array {
  const bytes = typeof value === "string" ? b64decode(value) : null;
  const [min, max] = typeof length === "number" ? [length, length] : length;
  if (!bytes || bytes.length < min || bytes.length > max) throw badRequest(`${field} is invalid`);
  return bytes;
}

export function parseIntParam(raw: string | null, field: string, fallback: number, min: number, max: number): number {
  if (raw === null) return fallback;
  if (!/^\d{1,15}$/.test(raw)) throw badRequest(`${field} must be an integer`);
  return expectInt(Number(raw), field, min, max);
}

export async function readJson(request: Request): Promise<unknown> {
  try {
    return await request.json();
  } catch {
    throw badRequest("body must be valid JSON");
  }
}
