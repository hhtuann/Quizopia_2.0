import { expect, test } from "@playwright/test";

test("login renders as a focused desktop product workflow", async ({
  page,
}) => {
  await page.goto("/login");

  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await expect(page.getByLabel("Username")).toBeVisible();
  await expect(page.getByLabel("Password")).toHaveAttribute("type", "password");
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
});

test("login remains within a 375px viewport", async ({ page }) => {
  await page.setViewportSize({ height: 812, width: 375 });
  await page.goto("/login");

  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("login controls are reachable in logical keyboard order", async ({
  page,
}) => {
  await page.goto("/login");

  await page.keyboard.press("Tab");
  await expect(
    page.getByRole("link", { name: "Skip to main content" }),
  ).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "Quizopia 2.0" })).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(page.getByLabel("Username")).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(page.getByLabel("Password")).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("button", { name: "Sign in" })).toBeFocused();
});

test("locally valid login reports unavailable integration without navigation", async ({
  page,
}) => {
  await page.goto("/login");
  await page.getByLabel("Username").fill("learner");
  await page.getByLabel("Password").fill("local-only-password");

  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page.getByRole("status")).toContainText(
    "Sign-in is not available yet",
  );
  await expect(page.getByRole("status")).toContainText("was not sent or saved");
  await expect(page).toHaveURL(/\/login$/);
});

test("registration remains readable without horizontal overflow on mobile", async ({
  page,
}) => {
  await page.setViewportSize({ height: 812, width: 375 });
  await page.goto("/register");

  await expect(
    page.getByRole("heading", { name: "Create your account" }),
  ).toBeVisible();
  await expect(page.getByLabel("Email address")).toBeVisible();
  await expect(page.getByLabel("Confirm password")).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("verification exposes no invented code countdown or attempt policy", async ({
  page,
}) => {
  await page.goto("/verify-email");

  await expect(
    page.getByRole("heading", { name: "Verify your email" }),
  ).toBeVisible();
  await expect(page.getByLabel("Verification code")).toHaveAttribute(
    "type",
    "text",
  );
  await expect(
    page.getByText(/seconds|attempts? remaining|resend in/i),
  ).toHaveCount(0);
});
