// Failure counters for /v1/setup and /v1/reset (SPEC §6): 10 failures within an hour lock the
// route for an hour.

export const MAX_FAILURES = 10;
export const WINDOW_MS = 60 * 60 * 1000;
export const LOCK_MS = 60 * 60 * 1000;

type LockoutRow = { window_start: number; failures: number; locked_until: number };

export type LockedRoute = "setup" | "reset";

export function isLocked(sql: SqlStorage, route: LockedRoute, now: number): boolean {
  const row = sql.exec<LockoutRow>("SELECT * FROM lockouts WHERE route = ?", route).toArray()[0];
  return row !== undefined && row.locked_until > now;
}

export function recordFailure(sql: SqlStorage, route: LockedRoute, now: number): void {
  const row = sql.exec<LockoutRow>("SELECT * FROM lockouts WHERE route = ?", route).toArray()[0];
  const fresh = !row || now - row.window_start >= WINDOW_MS;
  const windowStart = fresh ? now : row.window_start;
  const failures = fresh ? 1 : row.failures + 1;
  const lockedUntil = failures >= MAX_FAILURES ? now + LOCK_MS : 0;
  sql.exec(
    "INSERT OR REPLACE INTO lockouts (route, window_start, failures, locked_until) VALUES (?, ?, ?, ?)",
    route,
    windowStart,
    // After a lockout the count starts over, so the next window needs 10 fresh failures.
    lockedUntil ? 0 : failures,
    lockedUntil,
  );
}
