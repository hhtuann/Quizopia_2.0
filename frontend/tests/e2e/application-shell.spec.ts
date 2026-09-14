import { expect, test } from "@playwright/test";

test("application entry exposes the truthful current session limitation", async ({
  page,
}) => {
  await page.goto("/app");

  await expect(
    page.getByRole("heading", { name: "Open the Quizopia application" }),
  ).toBeVisible();
  await expect(page.getByRole("status")).toContainText(
    "Account access is not available yet",
  );
  await expect(page.getByRole("status")).toContainText(
    "Sign in is required to continue",
  );
  await expect(page.getByRole("status")).toContainText(
    "No session was created",
  );
});

test("application entry provides a working sign-in path", async ({ page }) => {
  await page.goto("/app");

  await page.getByRole("link", { name: "Go to sign in" }).click();

  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
});

test("application entry remains within a 375px viewport", async ({ page }) => {
  await page.setViewportSize({ height: 812, width: 375 });
  await page.goto("/app");

  await expect(
    page.getByRole("heading", { name: "Open the Quizopia application" }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("application entry supports skip navigation and visible keyboard focus", async ({
  page,
}) => {
  await page.goto("/app");

  await page.keyboard.press("Tab");
  const skipLink = page.getByRole("link", { name: "Skip to main content" });
  await expect(skipLink).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.locator("#main-content")).toBeFocused();

  await page.keyboard.press("Tab");
  const loginLink = page.getByRole("link", { name: "Go to sign in" });
  await expect(loginLink).toBeFocused();
  expect(
    await loginLink.evaluate((element) => {
      const style = getComputedStyle(element);
      return style.outlineStyle !== "none" || style.boxShadow !== "none";
    }),
  ).toBe(true);
});
