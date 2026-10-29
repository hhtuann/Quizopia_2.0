// Private stdin/stdout transport for QuizDeletionTopologyTest. No route interception,
// APIRequestContext, persisted tokens, HAR, screenshots, traces or local runtime files.
const path = require('node:path');
const readline = require('node:readline');
const { chromium } = require(path.resolve(__dirname, '../../frontend/node_modules/@playwright/test'));

async function main() {
  const browser = await chromium.launch({ headless: true });
  const ui = require('./fb015-ui.cjs')(browser);
  try {
    const page = await browser.newPage();
    page.setDefaultTimeout(20000);
    let origin;
    const input = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
    for await (const line of input) {
      const command = JSON.parse(line);
      if (command.close) break;
      try {
        if (command.ui) {
          console.log(JSON.stringify(await ui(command)));
          continue;
        }
        if (command.gateway) {
          origin = new URL(command.gateway).origin;
          if (new URL(origin).hostname !== '127.0.0.1') throw new Error('Loopback required');
          // A genuine document navigation establishes the Gateway origin. No mock page.
          await page.goto(origin + '/actuator/health');
          const status = await page.evaluate(async ({ identifier, password }) => {
            const response = await fetch('/api/auth/login', {
              method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ identifier, password }),
            });
            if (response.ok) window.fb015Token = (await response.json()).accessToken;
            return response.status;
          }, command);
          console.log(JSON.stringify({ status }));
          continue;
        }
        if (!command.path.startsWith('/') || command.path.startsWith('//')) throw new Error('Relative path required');
        const result = await page.evaluate(async (request) => {
          const headers = { 'Content-Type': 'application/json', ...request.headers };
          if (!request.anonymous) headers.Authorization = 'Bearer ' + window.fb015Token;
          const response = await fetch(request.path, {
            method: request.method, headers, redirect: 'error',
            body: request.body == null ? undefined : JSON.stringify(request.body),
            signal: AbortSignal.timeout(15000),
          });
          const text = await response.text();
          let body;
          try { body = JSON.parse(text); } catch { body = null; }
          return { status: response.status, body, cacheControl: response.headers.get('cache-control') };
        }, command);
        console.log(JSON.stringify(result));
      } catch (error) {
        if (command.ui) console.error(`FB015 UI ${command.ui} failed: ${error.message.replace(/Bearer\s+\S+/g, 'Bearer [redacted]').slice(0, 3000)}`);
        console.log(JSON.stringify({ error: 'Browser command failed (details suppressed)' }));
      }
    }
  } finally {
    await browser.close();
  }
}
main().catch(() => { console.error('FB015 Chromium driver failed'); process.exitCode = 1; });
