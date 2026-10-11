import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";
import { TEST_SETUP_TOKEN } from "./test/constants.ts";

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        bindings: { SETUP_TOKEN: TEST_SETUP_TOKEN },
        // Every test request shares one key, so lift the deployed limits; test/worker.test.ts
        // covers the 429 path with a stub limiter.
        ratelimits: {
          ANON_LIMITER: { namespace_id: "730101", simple: { limit: 1_000_000, period: 60 } },
          DEVICE_LIMITER: { namespace_id: "730102", simple: { limit: 1_000_000, period: 60 } },
        },
      },
    }),
  ],
  test: {
    // bin/test holds the CLI's node:test suite, run separately with `npm run test:cli`.
    include: ["test/**/*.test.ts"],
  },
});
