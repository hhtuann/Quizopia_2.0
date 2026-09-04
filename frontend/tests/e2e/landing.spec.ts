import { expect, test } from "@playwright/test";

test("development landing page renders", async ({ page }) => {
  await page.goto("/");
  await expect(
    page.getByText("Development scaffold is running."),
  ).toBeVisible();
});
