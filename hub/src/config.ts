// Deployment settings read from the Worker environment. Both fail closed.

import { HttpError } from "./errors";
import { isUsableSetupToken } from "./token";

export type Jurisdiction = "" | "eu";

/** SETUP_TOKEN is a secret, so it is absent from the generated Env type; read it defensively. */
export function setupTokenOf(env: Env): string {
  const value: unknown = Reflect.get(env, "SETUP_TOKEN");
  if (typeof value !== "string" || !isUsableSetupToken(value)) {
    throw new HttpError("unavailable", "hub is not configured: SETUP_TOKEN is missing or shorter than 32 characters");
  }
  return value;
}

/** The single Hub instance of this deployment, always named "hub". */
export function hubStub(env: Env): DurableObjectStub {
  const jurisdiction = jurisdictionOf(env);
  const namespace = jurisdiction === "" ? env.HUB : env.HUB.jurisdiction(jurisdiction);
  return namespace.get(namespace.idFromName("hub"));
}

export function jurisdictionOf(env: Env): Jurisdiction {
  const value: unknown = env.HUB_JURISDICTION ?? "";
  if (value !== "" && value !== "eu") {
    throw new HttpError("unavailable", 'hub is not configured: HUB_JURISDICTION must be "" or "eu"');
  }
  return value;
}
