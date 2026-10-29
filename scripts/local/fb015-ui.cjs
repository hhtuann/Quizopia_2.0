// UI rounds called by the private browser driver. Real Next pages, real clicks,
// real login/refresh and real Gateway responses; no route interception.
const path = require('node:path');
const { expect } = require(path.resolve(__dirname, '../../frontend/node_modules/@playwright/test'));
const copies = 'Primary data deleted · Waiting for backup copies to be erased';

module.exports = function createUi(browser) {
  let page, frontend;
  const posts = new Map();
  const latest = new Map();
  const operations = new Map();
  const card = id => page.locator('li').filter({ has: page.locator(`a[href="/app/quizzes/${id}"]`) });
  const apiResponse = (response, method, pathname) =>
    new URL(response.url()).origin === frontend && new URL(response.url()).pathname === pathname && response.request().method() === method;
  async function teaching() {
    const gate = page.getByRole('button', { name: 'Switch to Teaching', exact: true });
    if (await gate.waitFor({ state: 'visible', timeout: 2000 }).then(() => true).catch(() => false)) await gate.click();
  }
  async function noFalseSuccess() { await expect(page.getByText('Fully deleted', { exact: true })).toHaveCount(0); }
  return async command => {
    if (command.ui === 'login') {
      frontend = new URL(command.frontend).origin;
      const context = await browser.newContext();
      await context.addCookies([{ name: 'quizopia-locale', value: 'en', url: frontend }]);
      page = await context.newPage();
      page.setDefaultTimeout(20000);
      page.on('request', request => {
        const u = new URL(request.url());
        const match = u.pathname.match(/^\/api\/quizzes\/([^/]+)\/deletion-requests$/);
        if (u.origin === frontend && request.method() === 'POST' && match) posts.set(match[1], (posts.get(match[1]) || 0) + 1);
      });
      page.on('response', response => {
        const u = new URL(response.url());
        const match = u.pathname.match(/^\/api\/quizzes\/([^/]+)\/deletion-requests\/latest$/);
        if (u.origin === frontend && response.status() === 200 && match) latest.set(match[1], (latest.get(match[1]) || 0) + 1);
      });
      await page.goto(frontend + '/login');
      await page.getByLabel('Username or email').fill(command.identifier);
      await page.getByLabel('Password', { exact: true }).fill(command.password);
      const login = page.waitForResponse(r => apiResponse(r, 'POST', '/api/auth/login'));
      await page.getByRole('button', { name: 'Sign in', exact: true }).click();
      expect((await login).status()).toBe(200);
      await page.waitForURL(frontend + '/app');
      return { status: 200 };
    }
    if (command.ui === 'delete') {
      await page.goto(frontend + '/app/quizzes');
      await teaching();
      await expect(card(command.quizId)).toBeVisible();
      await card(command.quizId).getByRole('button', { name: 'Delete quiz', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: 'Delete this quiz permanently?' });
      await expect(dialog).toBeVisible();
      const confirm = dialog.getByRole('button', { name: 'Delete permanently', exact: true });
      await expect(confirm).toBeDisabled();
      if (command.title) {
        const field = dialog.getByLabel('Type the exact title to confirm');
        await field.fill(command.title.toLowerCase());
        await expect(confirm).toBeDisabled();
        await field.fill(command.title);
      } else {
        const checkbox = dialog.getByRole('checkbox', { name: 'I understand that permanently deleting this untitled quiz is irreversible.' });
        await expect(checkbox).not.toBeChecked();
        await checkbox.focus();
        await page.keyboard.press('Space');
        await expect(checkbox).toBeChecked();
      }
      await expect(confirm).toBeEnabled();
      const response = page.waitForResponse(r => apiResponse(r, 'POST', `/api/quizzes/${command.quizId}/deletion-requests`));
      await confirm.click();
      const accepted = await response;
      expect(accepted.status()).toBe(202);
      expect(accepted.request().postDataJSON()).toEqual({ confirmTitle: command.title || '' });
      const key = accepted.request().headers()['idempotency-key'];
      expect(key).toMatch(/^[A-Za-z0-9_-]{16,128}$/);
      const body = await accepted.json();
      operations.set(command.quizId, body.operationId);
      await expect(dialog).toHaveCount(0);
      await expect(card(command.quizId).getByRole('heading', { name: 'Deletion progress' })).toBeVisible();
      await noFalseSuccess();
      return { status: 202, body, idempotencyKey: key };
    }
    if (command.ui === 'recover') {
      // Clear ALL tab storage, rather than restoring any known operation id.
      await page.evaluate(() => sessionStorage.clear());
      expect(await page.evaluate(() => sessionStorage.length)).toBe(0);
      const before = latest.get(command.quizId) || 0;
      const discovery = page.waitForResponse(r => apiResponse(r, 'GET', `/api/quizzes/${command.quizId}/deletion-requests/latest`) && r.status() === 200);
      await page.reload();
      await teaching();
      const receipt = await (await discovery).json();
      expect(receipt.operationId).toBe(operations.get(command.quizId));
      await expect(card(command.quizId).getByText(command.text, { exact: true })).toBeVisible();
      await expect(card(command.quizId).getByRole('button', { name: 'Delete quiz', exact: true })).toHaveCount(0);
      expect(latest.get(command.quizId)).toBeGreaterThan(before);
      expect(posts.get(command.quizId)).toBe(1);
      await noFalseSuccess();
      return { status: 200, operationId: receipt.operationId };
    }
    if (command.ui === 'terminal') {
      await expect(page.getByText(copies, { exact: true })).toBeVisible({ timeout: 25000 });
      await noFalseSuccess();
      // A purged editor returns 404 for draft; the real UI must discover its receipt.
      await page.evaluate(() => sessionStorage.clear());
      const receipt = page.waitForResponse(r => apiResponse(r, 'GET', `/api/quizzes/${command.quizId}/deletion-requests/latest`) && r.status() === 200);
      await page.goto(frontend + `/app/quizzes/${command.quizId}`);
      await teaching();
      const body = await (await receipt).json();
      expect(body.operationId).toBe(operations.get(command.quizId));
      expect(body.status).toBe('PRIMARY_PURGED');
      expect(body.note).toBe('COPY_EXPIRY_PENDING');
      await expect(page.getByText(copies, { exact: true })).toBeVisible();
      await page.evaluate(() => sessionStorage.clear());
      const rediscovery = page.waitForResponse(r => apiResponse(r, 'GET', `/api/quizzes/${command.quizId}/deletion-requests/latest`) && r.status() === 200);
      await page.reload();
      await teaching();
      expect((await (await rediscovery).json()).operationId).toBe(body.operationId);
      await expect(page.getByText(copies, { exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Delete quiz', exact: true })).toHaveCount(0);
      await noFalseSuccess();
      expect(posts.get(command.quizId)).toBe(1);
      await page.goto(frontend + '/app/quizzes');
      await teaching();
      await expect(card(command.survivor)).toBeVisible();
      await expect(card(command.quizId)).toHaveCount(0);
      return { status: 200, operationId: body.operationId };
    }
    throw new Error('Unknown FB015 UI round');
  };
};
