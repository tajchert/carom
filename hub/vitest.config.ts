import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";
import { TEST_SETUP_TOKEN } from "./test/constants.ts";

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: { bindings: { SETUP_TOKEN: TEST_SETUP_TOKEN } },
    }),
  ],
  test: {
    // bin/test holds the CLI's node:test suite, run separately with `npm run test:cli`.
    include: ["test/**/*.test.ts"],
  },
});
