import { expect, test, type Page } from "@playwright/test";

// UX-10 regression: measures the RENDERED geometry of every shared select
// (appearance-none + custom chevron) in Chromium. Proves the chevron no
// longer hugs the right border: 10–18px chevron-to-border gap, ≥6px
// widest-option-text-to-chevron gap, vertically centered, and native
// semantics preserved (keyboard focus ring target is the select itself).
const VIEWPORTS = [
  { name: "1920x1080", width: 1920, height: 1080 },
  { name: "768x1024", width: 768, height: 1024 },
  { name: "390x844", width: 390, height: 844 },
] as const;

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
        accessToken: "ux10-token",
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

interface SelectGeometry {
  appearance: string;
  borderGap: number;
  textGap: number;
  centerDelta: number;
  optionCount: number;
}

async function measure(
  page: Page,
  selectLabel: string,
): Promise<SelectGeometry> {
  return page.evaluate((label) => {
    const select = Array.from(document.querySelectorAll("select")).find(
      (candidate) => {
        const own = candidate.getAttribute("aria-label");
        if (own && own.includes(label)) return true;
        const control = candidate.closest("label");
        return Boolean(control && control.textContent?.includes(label));
      },
    ) as HTMLSelectElement | null;
    if (!select) throw new Error(`select not found for ${label}`);
    const cs = getComputedStyle(select);
    const rect = select.getBoundingClientRect();
    // The shared wrapper renders exactly one decorative chevron sibling.
    const icon = select.parentElement!.querySelector("svg");
    if (!icon) throw new Error("custom chevron missing");
    const iconRect = icon.getBoundingClientRect();
    const probe = document.createElement("span");
    probe.style.cssText = `position:absolute;visibility:hidden;font:${cs.font};white-space:nowrap`;
    const widths = Array.from(select.options).map((option) => {
      probe.textContent = option.textContent;
      document.body.appendChild(probe);
      const width = probe.getBoundingClientRect().width;
      probe.remove();
      return width;
    });
    const textEnd =
      rect.left + parseFloat(cs.paddingLeft) + Math.max(...widths);
    return {
      appearance: cs.appearance,
      borderGap: rect.right - iconRect.right,
      textGap: iconRect.left - textEnd,
      centerDelta: Math.abs(
        iconRect.top + iconRect.height / 2 - (rect.top + rect.height / 2),
      ),
      optionCount: select.options.length,
    };
  }, selectLabel);
}

test.describe("UX-10 dropdown chevron geometry", () => {
  for (const viewport of VIEWPORTS) {
    test.describe(`viewport ${viewport.name}`, () => {
      test.use({
        viewport: { width: viewport.width, height: viewport.height },
      });

      test(`language select geometry at ${viewport.name}`, async ({ page }) => {
        await page.goto("/login");
        const language = page.getByRole("combobox", {
          name: /Ngôn ngữ|Language/,
        });
        await expect(language).toBeVisible();
        const geometry = await measure(page, "Ngôn ngữ");
        expect(geometry.appearance).toBe("none");
        expect(geometry.borderGap).toBeGreaterThanOrEqual(10);
        expect(geometry.borderGap).toBeLessThanOrEqual(18);
        expect(geometry.textGap).toBeGreaterThanOrEqual(6);
        expect(geometry.centerDelta).toBeLessThanOrEqual(2);
        // Native keyboard semantics: focusing the control shows its own ring.
        await language.focus();
        await expect(language).toBeFocused();
        await page
          .locator("select")
          .first()
          .screenshot({
            path: `test-results/ux10/language-${viewport.name}.png`,
          });
      });

      test(`directory state select geometry at ${viewport.name}`, async ({
        page,
      }) => {
        await mockTeacherBootstrap(page);
        await page.route("**/api/assessments/publications**", (route) =>
          route.fulfill({
            contentType: "application/json",
            status: 200,
            body: JSON.stringify({ items: [], nextCursor: null }),
          }),
        );
        await page.goto("/app/assessments");
        const stateSelect = page.getByRole("combobox", {
          name: /Publication state|Trạng thái/,
        });
        await expect(stateSelect).toBeVisible();
        // Locale-tolerant label key: vi renders "Trạng thái Publication".
        const geometry = await measure(page, "Publication");
        expect(geometry.appearance).toBe("none");
        expect(geometry.borderGap).toBeGreaterThanOrEqual(10);
        expect(geometry.borderGap).toBeLessThanOrEqual(18);
        expect(geometry.textGap).toBeGreaterThanOrEqual(6);
        expect(geometry.centerDelta).toBeLessThanOrEqual(2);
        expect(geometry.optionCount).toBe(4);
        // Selection semantics preserved end-to-end.
        await stateSelect.selectOption("OPEN");
        await expect(stateSelect).toHaveValue("OPEN");
        await stateSelect.screenshot({
          path: `test-results/ux10/state-${viewport.name}.png`,
        });
      });
    });
  }
});
