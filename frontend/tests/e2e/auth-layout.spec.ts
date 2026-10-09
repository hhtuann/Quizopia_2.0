import { expect, test, type Page } from "@playwright/test";

const sizes = [
  { width: 375, height: 812 },
  { width: 768, height: 1024 },
  { width: 1280, height: 720 },
  { width: 1440, height: 900 },
  { width: 1600, height: 900 },
] as const;

async function anonymous(page: Page) {
  await page.route("**/api/auth/refresh", (route) =>
    route.fulfill({
      status: 401,
      contentType: "application/json",
      body: JSON.stringify({
        code: "AUTH_REFRESH_FAILED",
        message: "Refresh failed.",
        status: 401,
      }),
    }),
  );
}

for (const size of sizes) {
  test(`auth layout ${size.width}x${size.height} remains aligned and accessible`, async ({
    page,
  }, testInfo) => {
    await anonymous(page);
    await page.setViewportSize(size);
    const welcomeRects: { x: number; y: number; width: number }[] = [];
    for (const route of ["login", "register", "verify-email"]) {
      await page.goto(`/${route}`);
      await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
      const welcome = page.getByTestId("auth-welcome");
      if (size.width >= 1024) {
        await expect(welcome).toBeVisible();
        const rect = await welcome.boundingBox();
        expect(rect).toBeTruthy();
        welcomeRects.push({ x: rect!.x, y: rect!.y, width: rect!.width });
        const cards = welcome.locator(".shadow-card");
        const first = await cards.nth(0).boundingBox();
        const second = await cards.nth(1).boundingBox();
        expect(first && second).toBeTruthy();
        expect(Math.abs(first!.x - second!.x)).toBeLessThan(1);
        expect(Math.abs(first!.width - second!.width)).toBeLessThan(1);
      } else {
        await expect(welcome).toBeHidden();
        const scroll = page.getByTestId("auth-form-scroll");
        expect(
          await scroll.evaluate(
            (element) => getComputedStyle(element).overflowY,
          ),
        ).not.toBe("auto");
      }
      await page.screenshot({
        path: testInfo.outputPath(
          `auth-${route}-${size.width}x${size.height}.png`,
        ),
        fullPage: size.width < 1024,
      });
    }

    for (const rect of welcomeRects.slice(1)) {
      expect(Math.abs(rect.y - welcomeRects[0]!.y)).toBeLessThan(2);
      expect(Math.abs(rect.x - welcomeRects[0]!.x)).toBeLessThan(2);
      expect(Math.abs(rect.width - welcomeRects[0]!.width)).toBeLessThan(2);
    }

    if (size.width >= 1024) {
      await page.goto("/register");
      const scroll = page.getByTestId("auth-form-scroll");
      const welcome = page.getByTestId("auth-welcome");
      const before = (await welcome.boundingBox())!.y;
      const scrollData = await scroll.evaluate((element) => {
        const before = element.scrollTop;
        element.scrollTop = element.scrollHeight;
        return {
          before,
          after: element.scrollTop,
          scrollHeight: element.scrollHeight,
          clientHeight: element.clientHeight,
        };
      });
      if (scrollData.scrollHeight > scrollData.clientHeight + 2) {
        expect(scrollData.after).toBeGreaterThan(scrollData.before);
      }
      expect(Math.abs((await welcome.boundingBox())!.y - before)).toBeLessThan(
        1,
      );
      await page.getByRole("button", { name: "Create account" }).focus();
      await expect(
        page.getByRole("button", { name: "Create account" }),
      ).toBeFocused();
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
    }
  });
}

test("verification OTP visual focus and invalid states", async ({
  page,
}, testInfo) => {
  await anonymous(page);
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto("/verify-email");
  const first = page.getByRole("textbox", {
    name: "Verification code digit 1 of 6",
  });
  await page.getByRole("textbox", { name: "Username" }).focus();
  await page.keyboard.press("Tab");
  await expect(first).toBeFocused();
  expect(
    await first.evaluate((element) => element.matches(":focus-visible")),
  ).toBe(true);
  await page.screenshot({
    path: testInfo.outputPath("otp-focused-mobile.png"),
  });
  await page.getByRole("button", { name: "Verify email" }).click();
  await expect(first).toHaveAttribute("aria-invalid", "true");
  await page.screenshot({
    path: testInfo.outputPath("otp-invalid-mobile.png"),
  });
});

test("tall registration form scrolls independently at a compact desktop height", async ({
  page,
}) => {
  await anonymous(page);
  await page.setViewportSize({ width: 1440, height: 660 });
  await page.goto("/register");

  const welcome = page.getByTestId("auth-welcome");
  const scroll = page.getByTestId("auth-form-scroll");
  const welcomeBefore = await welcome.boundingBox();
  expect(welcomeBefore).toBeTruthy();

  const dimensions = await scroll.evaluate((element) => ({
    scrollHeight: element.scrollHeight,
    clientHeight: element.clientHeight,
  }));
  expect(dimensions.scrollHeight).toBeGreaterThan(dimensions.clientHeight);
  await scroll.evaluate((element) => {
    element.scrollTop = element.scrollHeight;
  });
  expect(await scroll.evaluate((element) => element.scrollTop)).toBeGreaterThan(
    0,
  );
  expect(
    Math.abs((await welcome.boundingBox())!.y - welcomeBefore!.y),
  ).toBeLessThan(1);
  await expect(
    page.getByRole("button", { name: "Create account" }),
  ).toBeInViewport();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});
