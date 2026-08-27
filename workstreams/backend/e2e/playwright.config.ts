import { defineConfig } from '@playwright/test';

/**
 * API-level end-to-end configuration for the Sovereignty Lens backend.
 *
 * There are no browser projects on purpose: this suite talks HTTP and SSE to a
 * running Spring Boot service and never renders a page. No frontend exists yet.
 */
export default defineConfig({
  testDir: './tests',

  /**
   * Every spec mutates the ONE shared `demo` session on the server: it pauses
   * it, hides edges, and resets the round. Two tests running at the same time
   * would see each other's contributions and each other's round resets, so the
   * suite is strictly serial. `fullyParallel: false` keeps tests inside a file
   * sequential, and `workers: 1` keeps the files themselves from overlapping —
   * both are required, because with more than one worker Playwright still runs
   * separate files concurrently.
   */
  fullyParallel: false,
  workers: 1,

  // A stray `test.only` must never silently shrink the smoke suite on CI.
  forbidOnly: !!process.env.CI,

  // The stack is shared and stateful; a local red test should stay red.
  retries: process.env.CI ? 2 : 0,

  // SSE tests wait for a live frame to arrive, so allow more than the default.
  timeout: 30_000,
  expect: { timeout: 10_000 },

  reporter: [['list'], ['html', { open: 'never' }]],

  use: {
    baseURL: process.env.API_BASE_URL ?? 'http://localhost:8080',
    extraHTTPHeaders: { Accept: 'application/json' },
    // Errors are asserted on status + error.code, so never throw on non-2xx.
    ignoreHTTPSErrors: true,
    actionTimeout: 15_000,
    trace: 'off',
  },
});
