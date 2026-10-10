// Wake-up pushes (SPEC §9): only for devices without a live socket, at most one per device until
// it fetches again, with a re-push allowed after 5 minutes.

import { utf8 } from "./encoding";
import { getMeta, setMetaOnce } from "./store/meta";
import { clearPushSubscriptionIf, markPushSent, type DeviceRow } from "./store/devices";
import {
  VAPID_JWT_LIFETIME_S,
  createVapidJwt,
  decodeSubscriptionKeys,
  encryptPayload,
  generateVapidKeys,
  importVapidKeys,
  pushRequest,
  vapidAuthorization,
  type StoredVapidKeys,
  type VapidKeys,
} from "./webpush";

export const REPUSH_AFTER_MS = 5 * 60 * 1000;
const WAKE_PAYLOAD = utf8('{"t":"wake"}');
/** Reuse a cached JWT only while it has at least this much life left. */
const JWT_MIN_REMAINING_S = 60 * 60;

/**
 * The VAPID `sub`: the Hub URL (SPEC §9). RFC 8292 allows only https: or mailto:, so the scheme
 * is https even when a client reached the Hub over plain http.
 */
export function vapidSubject(requestUrl: URL): string {
  const url = new URL(requestUrl.origin);
  url.protocol = "https:";
  return url.origin;
}

export class PushSender {
  // In-memory caches only; both are rebuilt from SQLite after eviction.
  private keys: VapidKeys | null = null;
  private readonly jwts = new Map<string, { jwt: string; exp: number }>();

  constructor(
    private readonly sql: SqlStorage,
    private readonly ctx: DurableObjectState,
  ) {}

  /** Forgets cached keys, e.g. after a reset wiped the stored VAPID key. */
  clear(): void {
    this.keys = null;
    this.jwts.clear();
  }

  /** The VAPID key pair, created and stored on first use. */
  async vapid(): Promise<VapidKeys> {
    if (this.keys) return this.keys;
    let stored = getMeta(this.sql, "vapid");
    if (stored === null) {
      // Another request may create one while we generate; whichever is stored first wins.
      stored = setMetaOnce(this.sql, "vapid", JSON.stringify(await generateVapidKeys()));
    }
    this.keys = await importVapidKeys(JSON.parse(stored) as StoredVapidKeys);
    return this.keys;
  }

  /** Queues a wake-up push for `device` if it has a subscription and is not throttled. */
  wake(device: DeviceRow, subject: string, now: number): void {
    if (!device.push_endpoint || !device.push_p256dh || !device.push_auth) return;
    if (device.push_sent_at !== null && now - device.push_sent_at < REPUSH_AFTER_MS) return;
    markPushSent(this.sql, device.id, now);
    const { id, push_endpoint: endpoint, push_p256dh: p256dh, push_auth: auth } = device;
    this.ctx.waitUntil(this.send(id, endpoint, p256dh, auth, subject, now));
  }

  private async send(deviceId: string, endpoint: string, p256dh: string, auth: string, subject: string, now: number): Promise<void> {
    try {
      const keys = decodeSubscriptionKeys(p256dh, auth);
      if (!keys) return;
      const vapid = await this.vapid();
      const jwt = await this.jwtFor(vapid, new URL(endpoint).origin, subject, now);
      const body = await encryptPayload(keys, WAKE_PAYLOAD);
      const response = await fetch(pushRequest(endpoint, body, vapidAuthorization(jwt, vapid.publicKey)));
      await response.body?.cancel();
      if (response.status === 404 || response.status === 410) {
        clearPushSubscriptionIf(this.sql, deviceId, endpoint);
      } else if (!response.ok) {
        console.warn(JSON.stringify({ event: "push_failed", status: response.status }));
      }
    } catch (err) {
      console.warn(JSON.stringify({ event: "push_error", error: err instanceof Error ? err.name : "unknown" }));
    }
  }

  private async jwtFor(vapid: VapidKeys, audience: string, subject: string, now: number): Promise<string> {
    const key = `${audience} ${subject}`;
    const nowS = Math.floor(now / 1000);
    const cached = this.jwts.get(key);
    if (cached && cached.exp - nowS >= JWT_MIN_REMAINING_S) return cached.jwt;
    const exp = nowS + VAPID_JWT_LIFETIME_S;
    const jwt = await createVapidJwt(vapid.privateKey, audience, subject, exp);
    this.jwts.set(key, { jwt, exp });
    return jwt;
  }
}
