import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests',
  outputDir: './test-results/playwright',
  fullyParallel: true,
  use: { browserName: 'chromium', headless: true },
  reporter: [['list']],
  webServer: [
    { command: 'npm run dev:admin', url: 'http://127.0.0.1:5175/admin/', reuseExistingServer: !process.env.CI },
    { command: 'npm run dev:h5', url: 'http://127.0.0.1:5174/h5/', reuseExistingServer: !process.env.CI },
  ],
});
