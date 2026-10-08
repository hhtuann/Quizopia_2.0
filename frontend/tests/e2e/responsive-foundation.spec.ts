import { expect, test } from "@playwright/test";

const routes = [
  { heading: "A smarter space to learn and teach.", path: "/" },
  { heading: "Sign in", path: "/login" },
  { heading: "Create your account", path: "/register" },
  { heading: "Verify your email", path: "/verify-email" },
  { heading: "Sign in to continue", path: "/app" },
] as const;

const viewports = [
  { height: 812, name: "mobile", width: 375 },
  { height: 1024, name: "tablet", width: 768 },
  { height: 900, name: "laptop", width: 1280 },
  { height: 900, name: "desktop", width: 1440 },
  { height: 900, name: "wide desktop", width: 1600 },
] as const;

for (const viewport of viewports) {
  test(`${viewport.name} routes remain readable without viewport overflow`, async ({
    page,
  }) => {
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
    await page.setViewportSize({
      height: viewport.height,
      width: viewport.width,
    });

    for (const route of routes) {
      await page.goto(route.path);
      await expect(
        page.getByRole("heading", { level: 1, name: route.heading }),
      ).toBeVisible();
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
        `${route.path} should not overflow at ${viewport.width}px`,
      ).toBe(true);
    }
  });
}
