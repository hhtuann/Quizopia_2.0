import { expect, test, type Page } from "@playwright/test";

const user = {
  email: "learner01@gmail.com",
  id: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
  roles: ["STUDENT", "TEACHER"],
  username: "learner01",
};

async function mockAnonymousBootstrap(page: Page) {
  await page.route("**/api/auth/refresh", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      status: 401,
      body: JSON.stringify({
        code: "AUTH_REFRESH_FAILED",
        message: "Refresh failed.",
        path: "/api/auth/refresh",
        status: 401,
        traceId: null,
      }),
    });
  });
}

test("login renders the username-or-email workflow and remains responsive", async ({
  page,
}) => {
  await mockAnonymousBootstrap(page);
  await page.setViewportSize({ height: 812, width: 375 });
  await page.goto("/login");

  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await expect(page.getByLabel("Username or email")).toBeVisible();
  await expect(page.getByLabel("Password")).toHaveAttribute("type", "password");
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("login controls remain reachable in logical keyboard order", async ({
  page,
}) => {
  await mockAnonymousBootstrap(page);
  await page.goto("/login");

  await page.keyboard.press("Tab");
  await expect(
    page.getByRole("link", { name: "Skip to main content" }),
  ).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "Quizopia home" })).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(page.getByLabel("Username or email")).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(page.getByLabel("Password")).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("button", { name: "Sign in" })).toBeFocused();
});

test("intercepted login hydrates /me and opens the authenticated application", async ({
  page,
}) => {
  await mockAnonymousBootstrap(page);
  await page.route("**/api/auth/login", async (route) => {
    expect(route.request().postDataJSON()).toEqual({
      identifier: "learner01@gmail.com",
      password: "local-test-password",
    });
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        accessToken: "e2e-login-access",
        expiresIn: 300,
        tokenType: "Bearer",
      }),
    });
  });
  await page.route("**/api/auth/me", async (route) => {
    expect(route.request().headers().authorization).toBe(
      "Bearer e2e-login-access",
    );
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify(user),
    });
  });
  await page.goto("/login");

  await page.getByLabel("Username or email").fill("learner01@gmail.com");
  await page.getByLabel("Password").fill("local-test-password");
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page).toHaveURL(/\/app$/);
  await expect(
    page.getByRole("heading", { name: "Your Quizopia workspace" }),
  ).toBeVisible();
});

test("intercepted invalid login presents the generic credentials error", async ({
  page,
}) => {
  await mockAnonymousBootstrap(page);
  await page.route("**/api/auth/login", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      status: 401,
      body: JSON.stringify({
        code: "AUTH_INVALID_CREDENTIALS",
        message: "Invalid credentials.",
        path: "/api/auth/login",
        status: 401,
        traceId: null,
      }),
    });
  });
  await page.goto("/login");
  await page.getByLabel("Username or email").fill("learner01");
  await page.getByLabel("Password").fill("wrong-password");

  await page.getByRole("button", { name: "Sign in" }).click();

  const signInAlert = page
    .getByRole("alert")
    .filter({ hasText: "Sign in failed" });
  await expect(signInAlert).toContainText("Sign in failed");
  await expect(signInAlert).toContainText(
    "username/email or password is incorrect",
  );
  await expect(page).toHaveURL(/\/login$/);
});

test("registration success carries the username to verification without a role field", async ({
  page,
}) => {
  await mockAnonymousBootstrap(page);
  await page.route("**/api/auth/register", async (route) => {
    const body = route.request().postDataJSON();
    expect(body).toEqual({
      email: "learner01@gmail.com",
      password: "local-test-password",
      username: "learner+01",
    });
    expect(body).not.toHaveProperty("role");
    await route.fulfill({
      contentType: "application/json",
      status: 202,
      body: JSON.stringify({ status: "VERIFICATION_REQUIRED" }),
    });
  });
  await page.goto("/register");

  await page.getByLabel("Username").fill("learner+01");
  await page.getByLabel("Email address").fill("learner01@gmail.com");
  await page
    .getByLabel("Password", { exact: true })
    .fill("local-test-password");
  await page.getByLabel("Confirm password").fill("local-test-password");
  await page.getByRole("button", { name: "Create account" }).click();

  await expect(page).toHaveURL(/\/verify-email\?username=learner%2B01$/);
  await expect(page.getByLabel("Username")).toHaveValue("learner+01");
});

test("verification supports generic resend and confirms a six-digit OTP without creating a session", async ({
  page,
}) => {
  await mockAnonymousBootstrap(page);
  await page.route("**/api/auth/email-verification/request", async (route) => {
    expect(route.request().postDataJSON()).toEqual({ username: "learner01" });
    await route.fulfill({
      contentType: "application/json",
      status: 202,
      body: JSON.stringify({ status: "VERIFICATION_REQUEST_ACCEPTED" }),
    });
  });
  await page.route("**/api/auth/email-verification/confirm", async (route) => {
    expect(route.request().postDataJSON()).toEqual({
      otp: "123456",
      username: "learner01",
    });
    await route.fulfill({ status: 204 });
  });
  await page.goto("/verify-email?username=learner01");

  const code = page.getByRole("textbox", { name: /Verification code digit/ });
  await expect(code).toHaveCount(6);
  await expect(code.first()).toHaveAttribute("inputmode", "numeric");
  await page.getByRole("button", { name: "Resend code" }).click();
  await expect(page.getByRole("status")).toContainText(
    "If a verification code can be issued",
  );

  await code.first().fill("123456");
  await expect(code.nth(5)).toHaveValue("6");
  await page.getByRole("button", { name: "Verify email" }).click();

  await expect(page).toHaveURL(/\/login$/);
});

test("OTP supports keyboard editing, full paste, validation, failure and retry", async ({
  page,
}) => {
  await mockAnonymousBootstrap(page);
  let attempts = 0;
  await page.route("**/api/auth/email-verification/confirm", async (route) => {
    attempts += 1;
    expect(route.request().postDataJSON()).toEqual({
      username: "learner01",
      otp: "123456",
    });
    await route.fulfill(
      attempts === 1
        ? {
            status: 400,
            contentType: "application/json",
            body: JSON.stringify({
              code: "AUTH_VERIFICATION_FAILED",
              status: 400,
              message: "Invalid verification code",
              path: "/api/auth/email-verification/confirm",
              traceId: null,
            }),
          }
        : { status: 204 },
    );
  });
  await page.goto("/verify-email?username=learner01");
  const slots = page.getByRole("textbox", { name: /Verification code digit/ });
  await page.getByRole("button", { name: "Verify email" }).click();
  await expect(
    page.getByText("Enter the six-digit verification code."),
  ).toBeVisible();
  await expect(slots.first()).toHaveAttribute("aria-invalid", "true");
  await slots.first().fill("12");
  await expect(slots.nth(1)).toHaveValue("2");
  await slots.nth(1).press("ArrowRight");
  await expect(slots.nth(2)).toBeFocused();
  await slots.nth(2).press("Backspace");
  await expect(slots.nth(1)).toBeFocused();
  await expect(slots.nth(1)).toHaveValue("");
  await slots.first().focus();
  await page
    .evaluate(() => navigator.clipboard?.writeText("123456"))
    .catch(() => {});
  await slots.first().press("ControlOrMeta+V");
  // Explicit paste is also tested for browsers that deny clipboard permissions.
  if ((await slots.nth(5).inputValue()) !== "6") {
    await slots.first().evaluate((element) => {
      const data = new DataTransfer();
      data.setData("text/plain", "123456");
      element.dispatchEvent(
        new ClipboardEvent("paste", {
          bubbles: true,
          cancelable: true,
          clipboardData: data,
        }),
      );
    });
  }
  for (let digit = 0; digit < 6; digit++) {
    await expect(slots.nth(digit)).toHaveValue(String(digit + 1));
  }
  await page.getByRole("button", { name: "Verify email" }).click();
  await expect(
    page.getByRole("alert").filter({ hasText: "Email verification failed" }),
  ).toBeVisible();
  await expect(slots.first()).toHaveValue("1");
  await page.getByRole("button", { name: "Verify email" }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect(attempts).toBe(2);
});
