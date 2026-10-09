import { expect, test } from "@playwright/test";

test("Quizopia landing page renders and supports skip navigation", async ({
  page,
}) => {
  await page.goto("/");

  await expect(
    page.getByRole("heading", { name: "A smarter space to learn and teach." }),
  ).toBeVisible();
  for (const name of ["Get started", "Create your account"]) {
    const link = page.getByRole("link", { name });
    await expect(link).toHaveAttribute("href", "/register");
    await expect(link.locator("svg[aria-hidden='true']")).toHaveCount(1);
  }
  for (const heading of ["Learning", "Teaching"]) {
    await expect(
      page.getByRole("heading", { name: heading }).locator("..").locator("svg"),
    ).toHaveCount(1);
  }

  await page.keyboard.press("Tab");
  const skipLink = page.getByRole("link", { name: "Skip to main content" });
  await expect(skipLink).toBeFocused();
  await expect(skipLink).toBeVisible();

  await page.keyboard.press("Enter");
  await expect(page.locator("#main-content")).toBeFocused();
});

test("landing SVG icons stay visible and aligned at desktop and mobile widths", async ({
  page,
}, testInfo) => {
  for (const width of [375, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/");
    await expect(
      page.getByRole("link", { name: "Get started" }).locator("svg"),
    ).toBeVisible();
    await expect(
      page.getByRole("link", { name: "Create your account" }).locator("svg"),
    ).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await page.screenshot({
      path: testInfo.outputPath(`landing-${width}x900.png`),
      fullPage: true,
    });
  }
});
