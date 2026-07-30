import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end configuration for the accessibility gate and the permission-tamper check
 * (UI-5, F-06.4 / T-06.3.2.3).
 *
 * Runs against a production build (`next build` + `next start`), not the dev server: the dev
 * overlay injects its own DOM, and an axe violation reported against markup that never ships
 * is a false alarm that trains people to ignore the gate.
 *
 * The API is stubbed at the browser's network boundary (see `e2e/fixtures/mock-api.ts`), so the
 * suite needs no Java, no PostgreSQL and no seeded data. That is the right seam for this story:
 * what is under test is the portal's markup and its client-side behaviour. Server-side
 * authorization is proven where it lives, by ApiAuthorizationIT against a real database.
 */
export default defineConfig({
  testDir: "./e2e",
  // Vitest owns tests/**; Playwright owns e2e/** — no overlap, so `npm test` and `npm run e2e`
  // never collect each other's files.
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  // `github` annotates the failing line in the PR diff; `html` is what the CI job uploads as an
  // artefact and what `npm run e2e:report` opens locally. Never auto-open — it would hang CI.
  reporter: [
    ["list"],
    ["html", { open: "never" }],
    ...(process.env.CI ? [["github"] as const] : []),
  ],
  use: {
    baseURL: "http://localhost:3000",
    trace: "on-first-retry",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: {
    command: "npm run start",
    url: "http://localhost:3000",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
