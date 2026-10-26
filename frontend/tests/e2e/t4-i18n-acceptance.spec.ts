import { expect, test, type Page } from "@playwright/test";

// T4 bilingual acceptance: default Vietnamese, immediate user-controlled
// switching to English and back, cookie persistence across reloads, fresh
// sessions falling back to vi, and authenticated surfaces rendering in both
// locales. Browser-contract tests with intercepted Gateway responses.
const teacher = {
  email: "teacher01@gmail.com",
  id: "a7a4c564-3c27-4e6d-91aa-a004334aa8f9",
  roles: ["TEACHER"],
  username: "teacher01",
};

async function mockTeacherBootstrap(page: Page) {
  await page.route("**/api/auth/refresh", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        accessToken: "t4-i18n-token",
        expiresIn: 300,
        tokenType: "Bearer",
      }),
    }),
  );
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify(teacher),
    }),
  );
}

test.describe("i18n acceptance", () => {
  test.setTimeout(90_000);

  test("fresh session defaults to Vietnamese with correct document language", async ({
    page,
  }) => {
    await page.goto("/login");
    await expect(
      page.getByRole("heading", { name: "Đăng nhập", exact: true }),
    ).toBeVisible();
    await expect(page.locator("html")).toHaveAttribute("lang", "vi");
    await expect(
      page.getByRole("button", { name: "Đăng nhập", exact: true }),
    ).toBeVisible();
  });

  test("switching to English is immediate, persistent, and reversible", async ({
    page,
  }) => {
    await page.goto("/login");
    await expect(
      page.getByRole("heading", { name: "Đăng nhập", exact: true }),
    ).toBeVisible();

    // Switch to English through the visible control; no navigation/reload.
    await page.getByRole("button", { name: /Ngôn ngữ: |Language: / }).click();
    await page.getByRole("menuitemradio", { name: "English" }).click();
    await expect(
      page.getByRole("heading", { name: "Sign in", exact: true }),
    ).toBeVisible();
    await expect(page.locator("html")).toHaveAttribute("lang", "en");

    // Preference persists across a full reload.
    await page.reload();
    await expect(
      page.getByRole("heading", { name: "Sign in", exact: true }),
    ).toBeVisible();
    await expect(page.locator("html")).toHaveAttribute("lang", "en");

    // And back to Vietnamese.
    await page.getByRole("button", { name: /Ngôn ngữ: |Language: / }).click();
    await page.getByRole("menuitemradio", { name: "Tiếng Việt" }).click();
    await expect(
      page.getByRole("heading", { name: "Đăng nhập", exact: true }),
    ).toBeVisible();
    await expect(page.locator("html")).toHaveAttribute("lang", "vi");
  });

  test("authenticated Teaching surfaces render in both locales", async ({
    page,
  }) => {
    await mockTeacherBootstrap(page);
    await page.route("**/api/quizzes", (route) =>
      route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify({ items: [], nextCursor: null }),
      }),
    );
    await page.route("**/api/assessments/publications**", (route) =>
      route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify({ items: [], nextCursor: null }),
      }),
    );

    await page.goto("/app/assessments");
    await expect(
      page.getByRole("heading", { name: /Bài kiểm tra của tôi/ }),
    ).toBeVisible();
    await expect(page.getByText("Chưa có bài kiểm tra nào")).toBeVisible();

    // Authenticated pages expose the language options through the user menu
    // (menuitemradio), distinct from the directory's state filter select. The
    // trigger label is localized, so match it in either UI language.
    const userMenu = page.getByRole("button", {
      name: /Open user menu for teacher01|Mở menu người dùng cho teacher01/,
    });
    await userMenu.click();
    const languageGroup = page.getByRole("group", {
      name: "Language / Ngôn ngữ",
    });
    await expect(
      languageGroup.getByRole("menuitemradio", { name: "Tiếng Việt" }),
    ).toHaveAttribute("aria-checked", "true");
    await languageGroup.getByRole("menuitemradio", { name: "English" }).click();
    await expect(
      page.getByRole("heading", { name: "My Assessments" }),
    ).toBeVisible();
    await expect(page.getByText("No assessments yet")).toBeVisible();
    // The workspace context is unchanged by the language switch.
    await expect(
      page.getByRole("button", { name: /Open user menu for teacher01/ }),
    ).toBeVisible();
  });
});
