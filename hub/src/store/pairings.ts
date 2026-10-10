// Pairing offers (SPEC §8): waiting -> accepted (pending candidate) -> done (welcome stored).

export type PairingState = "waiting" | "accepted" | "done";

export type PairingRow = {
  pair_id: string;
  offerer: string;
  state: PairingState;
  expires_at: number;
  candidate: string | null;
  mac: string | null;
  pop: string | null;
  box: string | null;
};

export const PAIRING_TTL_MS = 5 * 60 * 1000;
export const MAX_OPEN_OFFERS = 5;

export function getPairing(sql: SqlStorage, pairId: string): PairingRow | null {
  return sql.exec<PairingRow>("SELECT * FROM pairings WHERE pair_id = ?", pairId).toArray()[0] ?? null;
}

/** Offers that have not been welcomed yet and have not expired. */
export function countOpenOffers(sql: SqlStorage, now: number): number {
  return sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM pairings WHERE state != 'done' AND expires_at > ?", now).one().n;
}

export function insertOffer(sql: SqlStorage, pairId: string, offerer: string, expiresAt: number): void {
  sql.exec("INSERT INTO pairings (pair_id, offerer, state, expires_at) VALUES (?, ?, 'waiting', ?)", pairId, offerer, expiresAt);
}

export function markAccepted(sql: SqlStorage, pairId: string, candidate: string, mac: string, pop: string): void {
  sql.exec("UPDATE pairings SET state = 'accepted', candidate = ?, mac = ?, pop = ? WHERE pair_id = ?", candidate, mac, pop, pairId);
}

export function markDone(sql: SqlStorage, pairId: string, box: string, expiresAt: number): void {
  sql.exec("UPDATE pairings SET state = 'done', box = ?, expires_at = ? WHERE pair_id = ?", box, expiresAt, pairId);
}

export function deletePairing(sql: SqlStorage, pairId: string): void {
  sql.exec("DELETE FROM pairings WHERE pair_id = ?", pairId);
}

/** Deletes pairings matching `where`, along with candidates that never got activated. */
function deleteWithPendingCandidates(sql: SqlStorage, where: string, arg: string | number): void {
  sql.exec(
    `DELETE FROM devices WHERE status = 'pending'
     AND id IN (SELECT candidate FROM pairings WHERE candidate IS NOT NULL AND ${where})`,
    arg,
  );
  sql.exec(`DELETE FROM pairings WHERE ${where}`, arg);
}

export function expirePairings(sql: SqlStorage, now: number): void {
  deleteWithPendingCandidates(sql, "expires_at <= ?", now);
}

export function deletePairingsOf(sql: SqlStorage, offerer: string): void {
  deleteWithPendingCandidates(sql, "offerer = ?", offerer);
}

export function earliestPairingExpiry(sql: SqlStorage): number | null {
  return sql.exec<{ t: number | null }>("SELECT MIN(expires_at) AS t FROM pairings").one().t;
}
