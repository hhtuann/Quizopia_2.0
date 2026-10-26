import { expect, test, type Page } from "@playwright/test";

// These journeys assert the English UI. The application defaults to Vietnamese;
// pin the preference cookie before any navigation so assertions stay stable.
test.beforeEach(async ({ page }) => {
  await page
    .context()
    .addCookies([
      { name: "quizopia-locale", value: "en", url: "http://localhost:3000" },
    ]);
});

async function unavailableLogin(page: Page) {
  await page.route("**/api/auth/refresh", (route) =>
    route.fulfill({
      status: 401,
      contentType: "application/json",
      body: JSON.stringify({
        code: "AUTH_REFRESH_FAILED",
        message: "Refresh failed.",
        path: "/api/auth/refresh",
        status: 401,
        traceId: null,
      }),
    }),
  );
  await page.route("**/api/auth/login", (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        accessToken: "mock-candidate",
        expiresIn: 300,
        tokenType: "Bearer",
      }),
    }),
  );
  let recovered = false;
  let meCalls = 0;
  await page.route("**/api/auth/me", (route) => {
    meCalls += 1;
    return recovered
      ? route.fulfill({
          contentType: "application/json",
          body: JSON.stringify({
            id: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
            email: "learner@gmail.com",
            username: "learner",
            roles: ["STUDENT"],
          }),
        })
      : route.fulfill({ status: 503 });
  });
  await page.goto("/login");
  await page.getByLabel("Username or email").fill("learner");
  await page.getByLabel("Password").fill("test-password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page
      .getByRole("alert")
      .filter({ hasText: "Account verification is temporarily unavailable" }),
  ).toContainText("Account verification is temporarily unavailable");
  return {
    recover: () => {
      recovered = true;
    },
    calls: () => meCalls,
  };
}

test("manual verification is keyboard accessible, announces repeated failure and fits mobile", async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 375, height: 812 });
  const h = await unavailableLogin(page);
  const retry = page.getByRole("button", {
    name: "Retry account verification",
  });
  await retry.focus();
  await expect(retry).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(
    page.getByRole("status", { name: "Verification progress" }),
  ).toContainText("Verification attempt 1 could not be completed");
  await expect(page).toHaveURL(/\/login$/);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: testInfo.outputPath("verification-mobile.png"),
    fullPage: true,
  });
  h.recover();
  await retry.focus();
  await page.keyboard.press("Space");
  await expect(page).toHaveURL(/\/app$/);
  expect(h.calls()).toBe(3);
});

test("queued native Web Lock times out without a request and permits later recovery", async ({
  page,
}) => {
  const h = await unavailableLogin(page);
  await page.clock.install();
  await page.evaluate(() => {
    const state = window as typeof window & {
      releaseAuthLock?: () => void;
      authLockHeld?: boolean;
    };
    void navigator.locks.request(
      "quizopia-auth-refresh",
      () =>
        new Promise<void>((resolve) => {
          state.releaseAuthLock = resolve;
          state.authLockHeld = true;
        }),
    );
  });
  await page.waitForFunction(
    () => (window as typeof window & { authLockHeld?: boolean }).authLockHeld,
  );
  await page
    .getByRole("button", { name: "Retry account verification" })
    .click();
  await expect(
    page.getByRole("button", { name: "Verifying account" }),
  ).toBeDisabled();
  await page.clock.fastForward(10_001);
  await expect(
    page.getByRole("status", { name: "Verification progress" }),
  ).toContainText("Verification attempt 1 could not be completed");
  expect(h.calls()).toBe(1);
  await page.evaluate(() =>
    (
      window as typeof window & { releaseAuthLock?: () => void }
    ).releaseAuthLock?.(),
  );
  h.recover();
  await page
    .getByRole("button", { name: "Retry account verification" })
    .click();
  await expect(page).toHaveURL(/\/app$/);
  expect(h.calls()).toBe(2);
});
