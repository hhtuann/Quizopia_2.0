import { expect, test, type Page } from "@playwright/test";

// Leader-correction regression: header geometry parity between the focused
// Teacher Quiz Editor and Student Attempt workspaces, desktop workspace
// width utilization, and the flagged/answered navigator presentation.
const student = {
  email: "learner01@gmail.com",
  id: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
  roles: ["STUDENT"],
  username: "learner01",
};
const teacher = {
  email: "teacher01@gmail.com",
  id: "a7a4c564-3c27-4e6d-91aa-a004334aa8f9",
  roles: ["TEACHER"],
  username: "teacher01",
};
const attemptId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const quizId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";

async function mockAuth(page: Page, user: typeof student) {
  await page.route("**/api/auth/refresh", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        accessToken: "ux-correction-token",
        expiresIn: 300,
        tokenType: "Bearer",
      }),
    }),
  );
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify(user),
    }),
  );
}

const attemptView = {
  attemptId,
  publicationId: quizId,
  ordinal: "1",
  state: "ACTIVE",
  startedAt: "2026-10-16T08:00:00Z",
  deadlineAt: "2099-10-16T08:40:00Z",
  durationMinutes: 40,
  revision: "0",
  serverNow: "2026-10-16T08:01:00Z",
  questions: [1, 2, 3, 4].map((n) => ({
    number: n,
    type: n === 4 ? "NUMERIC_FILL" : "SINGLE_CHOICE",
    stemMarkdown: `Question ${n} stem?`,
    options:
      n === 4
        ? []
        : [
            { label: "A", markdown: "Alpha" },
            { label: "B", markdown: "Beta" },
          ],
  })),
  answers: [1, 2, 3, 4].map((n) => ({
    questionNumber: n,
    answer:
      n === 4
        ? { type: "NUMERIC_FILL", value: null }
        : { type: "SINGLE_CHOICE", optionLabel: null },
  })),
  closure: null,
};

async function mockAttempt(page: Page) {
  await page.route(`**/api/assessments/attempts/${attemptId}`, (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify(attemptView),
    }),
  );
  await page.route(
    `**/api/assessments/attempts/${attemptId}/questions/*/answer`,
    (route) =>
      route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify({
          attemptId,
          questionNumber: 1,
          revision: "1",
          savedAt: "2026-10-16T08:05:00Z",
        }),
      }),
  );
}

async function mockDraft(page: Page) {
  await page.route(`**/api/quizzes/${quizId}/draft`, (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        quizId,
        title: "Geometry parity quiz",
        deletionStatus: null,
        description: "",
        authoringSource: "Câu 1 [SINGLE_CHOICE]: One?\n*A. Yes\nB. No",
        createdAt: "2026-10-01T07:00:00Z",
        updatedAt: "2026-10-01T07:00:00Z",
      }),
    }),
  );
}

async function headerGeometry(page: Page) {
  return page.evaluate(() => {
    const header = document.querySelector("header")!;
    const back = Array.from(header.querySelectorAll("button")).find((b) =>
      (b.getAttribute("aria-label") ?? "").includes("Back to"),
    )!;
    const mark = header.querySelector('[data-testid="quizopia-brand-mark"]')!;
    const headerRect = header.getBoundingClientRect();
    const backRect = back.getBoundingClientRect();
    const markRect = mark.getBoundingClientRect();
    return {
      headerHeight: headerRect.height,
      backSize: { w: backRect.width, h: backRect.height },
      backIsIconOnly: back.textContent?.trim() === "",
      markSize: { w: markRect.width, h: markRect.height },
      languageControls: header.querySelectorAll(
        '[aria-label*="Language"], [aria-label*="Ngôn ngữ"], select',
      ).length,
    };
  });
}

async function pinEnglish(page: Page) {
  await page
    .context()
    .addCookies([
      { name: "quizopia-locale", value: "en", url: "http://localhost:3000" },
    ]);
}

test.describe("UX correction: focused workspace parity", () => {
  test.describe("header geometry", () => {
    test.use({ viewport: { width: 1440, height: 900 } });

    test("editor and attempt headers share geometry and carry no language control", async ({
      page,
    }) => {
      await pinEnglish(page);

      // Teacher editor header
      await mockAuth(page, teacher);
      await mockDraft(page);
      await page.goto(`/app/quizzes/${quizId}`);
      await page.getByRole("textbox", { name: "Quiz title" }).waitFor();
      const editor = await headerGeometry(page);
      await page.screenshot({
        path: "test-results/ux-correction/editor-header-1440.png",
      });

      // Student attempt header
      await mockAuth(page, student);
      await mockAttempt(page);
      await page.goto(`/app/attempts/${attemptId}`);
      await page.getByRole("heading", { name: "Assessment" }).waitFor();
      const attempt = await headerGeometry(page);
      await page.screenshot({
        path: "test-results/ux-correction/attempt-header-1440.png",
      });

      // No language control in either focused header.
      expect(editor.languageControls).toBe(0);
      expect(attempt.languageControls).toBe(0);
      // Icon-only back buttons with identical geometry.
      expect(editor.backIsIconOnly).toBe(true);
      expect(attempt.backIsIconOnly).toBe(true);
      expect(
        Math.abs(editor.backSize.w - attempt.backSize.w),
      ).toBeLessThanOrEqual(1);
      expect(
        Math.abs(editor.backSize.h - attempt.backSize.h),
      ).toBeLessThanOrEqual(1);
      // Same brand mark size and header height.
      expect(
        Math.abs(editor.markSize.w - attempt.markSize.w),
      ).toBeLessThanOrEqual(1);
      expect(
        Math.abs(editor.headerHeight - attempt.headerHeight),
      ).toBeLessThanOrEqual(2);
    });
  });

  test.describe("desktop width utilization", () => {
    test.use({ viewport: { width: 1920, height: 1080 } });

    test("desktop attempt workspace uses viewport width with a right navigator", async ({
      page,
    }) => {
      await pinEnglish(page);
      await mockAuth(page, student);
      await mockAttempt(page);
      await page.goto(`/app/attempts/${attemptId}`);
      await page.getByRole("heading", { name: "Assessment" }).waitFor();

      const metrics = await page.evaluate(() => {
        const doc = document.documentElement;
        const main = document.querySelector("main")!;
        const mainRect = main.getBoundingClientRect();
        const navigator = document.querySelector(
          'nav[aria-label="Attempt questions"]',
        )!;
        const navRect = navigator.getBoundingClientRect();
        const firstCard = document.querySelector("[data-question-number]")!;
        const cardRect = firstCard.getBoundingClientRect();
        return {
          viewport: doc.clientWidth,
          contentWidth: mainRect.width,
          leftGutter: mainRect.left,
          rightGutter: doc.clientWidth - mainRect.right,
          navigatorRight: doc.clientWidth - navRect.right,
          navigatorLeft: navRect.left,
          cardWidth: cardRect.width,
          docOverflow: doc.scrollWidth - doc.clientWidth,
        };
      });
      // Full-width workspace: never a narrow centered 700–900px column.
      expect(metrics.contentWidth).toBeGreaterThan(1500);
      expect(metrics.leftGutter).toBeLessThanOrEqual(40);
      expect(metrics.rightGutter).toBeLessThanOrEqual(40);
      expect(metrics.docOverflow).toBeLessThanOrEqual(1);
      // Navigator sits at the right side; question cards take the dominant
      // flexible column (~1570px minus the 288px navigator and gap).
      expect(metrics.navigatorRight).toBeLessThanOrEqual(40);
      expect(metrics.navigatorLeft).toBeGreaterThan(1500);
      expect(metrics.cardWidth).toBeGreaterThan(1300);
    });
  });

  test.describe("flags", () => {
    test.use({ viewport: { width: 1440, height: 900 } });

    test("flags toggle per card and reflect in navigator boxes without answers changing", async ({
      page,
    }) => {
      await pinEnglish(page);
      await mockAuth(page, student);
      await mockAttempt(page);
      await page.goto(`/app/attempts/${attemptId}`);
      await page.getByRole("heading", { name: "Assessment" }).waitFor();

      const flag2 = page.getByRole("button", { name: "Flag question 2" });
      await expect(flag2).toHaveAttribute("aria-pressed", "false");
      await flag2.click();
      await expect(
        page.getByRole("button", { name: "Unflag question 2" }),
      ).toHaveAttribute("aria-pressed", "true");
      // Navigator box label includes the flag and keeps the underlying state.
      const box = page.getByRole("button", {
        name: "Question 2, Flagged · Unanswered · Saved",
      });
      await expect(box).toBeVisible();
      // Box visual: amber border, no checkmark svg, no purple fill.
      const classes = await box.getAttribute("class");
      expect(classes).toContain("border-warning");
      expect(classes).not.toContain("bg-primary");
      expect(await box.locator("svg").count()).toBe(0);
      // Unflag returns the neutral box.
      await page.getByRole("button", { name: "Unflag question 2" }).click();
      await expect(
        page.getByRole("button", { name: "Question 2, Unanswered · Saved" }),
      ).toBeVisible();
    });

    test("answered boxes use green borders and current box never purple", async ({
      page,
    }) => {
      await pinEnglish(page);
      await mockAuth(page, student);
      await mockAttempt(page);
      await page.goto(`/app/attempts/${attemptId}`);
      await page.getByRole("heading", { name: "Assessment" }).waitFor();

      await page.locator("label", { hasText: "Alpha" }).first().click();
      // The current box (question 1) must not use a solid purple fill.
      const current = page.getByRole("button", {
        name: /Question 1, Answered/,
      });
      await expect(current).toBeVisible();
      const classes = await current.getAttribute("class");
      expect(classes).not.toContain("bg-primary");
      expect(classes).toContain("ring-foreground/40");
      expect(await current.locator("svg").count()).toBe(0);
      // Answered boxes use green borders in every honest save phase (the
      // phase only shifts the border family while saving/failed, both of
      // which remain non-purple, non-checkmark states).
      expect(classes).toMatch(/border-(success|info|danger)/);
    });
  });

  test("viewport sweep keeps the workspace overflow-free in both locales", async ({
    page,
  }) => {
    for (const locale of ["en", "vi"]) {
      for (const vp of [
        { name: "1920x1080", width: 1920, height: 1080 },
        { name: "1440x900", width: 1440, height: 900 },
        { name: "768x1024", width: 768, height: 1024 },
        { name: "390x844", width: 390, height: 844 },
      ]) {
        await page.setViewportSize({ width: vp.width, height: vp.height });
        await page.context().clearCookies();
        await page.context().addCookies([
          {
            name: "quizopia-locale",
            value: locale,
            url: "http://localhost:3000",
          },
        ]);
        await mockAuth(page, student);
        await mockAttempt(page);
        await page.goto(`/app/attempts/${attemptId}`);
        await page.getByRole("heading", { name: /.+/ }).waitFor();
        expect(
          await page.evaluate(
            () =>
              document.documentElement.scrollWidth -
              document.documentElement.clientWidth,
          ),
          `${locale} ${vp.name} overflow`,
        ).toBeLessThanOrEqual(1);
        await page.screenshot({
          path: `test-results/ux-correction/attempt-${locale}-${vp.name}.png`,
          fullPage: true,
        });
      }
    }
  });
});
