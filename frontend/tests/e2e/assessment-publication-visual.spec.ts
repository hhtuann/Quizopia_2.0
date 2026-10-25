import { expect, test, type Locator, type Page } from "@playwright/test";

// Visual/layout regression coverage for the Participation schedule DateTimeField
// pair on the assessment create screen. Verifies at desktop, tablet and phone
// viewports that the native date/time controls never clip, never overflow their
// container and never cause horizontal document scroll, in 12-hour (en-US) and
// 24-hour (en-GB) locales. Screenshots are written to EVIDENCE_DIR when set
// (otherwise the default test-results/visual output directory).

const quizId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const quizVersionId = "9bd4c564-3c27-4e6d-91aa-a004334aa8f7";
const evidenceDir = process.env.EVIDENCE_DIR ?? "test-results/visual";

const VIEWPORTS = [
  { name: "1920x1080", width: 1920, height: 1080 },
  { name: "1440x900", width: 1440, height: 900 },
  { name: "768x1024", width: 768, height: 1024 },
  { name: "390x844", width: 390, height: 844 },
] as const;

async function mockAuth(page: Page) {
  await page.route("**/api/auth/refresh", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        accessToken: "visual-evidence-token",
        expiresIn: 300,
        tokenType: "Bearer",
      }),
    }),
  );
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        id: "a7a4c564-3c27-4e6d-91aa-a004334aa8f9",
        username: "teacher",
        email: "teacher@gmail.com",
        roles: ["TEACHER"],
      }),
    }),
  );
}

async function mockQuizVersion(page: Page) {
  await page.route(`**/api/quizzes/${quizId}/versions/3`, (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        id: quizVersionId,
        quizId,
        versionNumber: 3,
        titleSnapshot: "Pinned version assessment",
        descriptionSnapshot: "Version three",
        contentSchemaVersion: 1,
        createdAt: "2026-10-16T07:00:00Z",
        sourceSnapshot: "Câu 1 [NUMERIC_FILL]: Published source?\nĐáp án: 42",
        structuredContent: {
          questions: [
            {
              number: 1,
              type: "NUMERIC_FILL",
              stemMarkdown: "Published source?",
              options: [],
              numericAnswer: "42",
              explanationMarkdown: null,
            },
          ],
        },
      }),
    }),
  );
}

async function openCreateForm(page: Page) {
  await mockAuth(page);
  await mockQuizVersion(page);
  await page.goto(
    `/app/assessments/new?quizId=${quizId}&quizVersionId=${quizVersionId}&versionNumber=3`,
  );
  // CLASS audience hides the schedule until Public is selected.
  await page.getByRole("radio", { name: "Public", exact: true }).check();
  await expect(
    page.getByLabel("Participation start", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByLabel("Participation end", { exact: true }),
  ).toBeVisible();
}

async function assertNoClipping(page: Page): Promise<Locator> {
  // Document-level: no horizontal scroll.
  const docOverflow = await page.evaluate(
    () =>
      document.documentElement.scrollWidth -
      document.documentElement.clientWidth,
  );
  expect(docOverflow).toBeLessThanOrEqual(1);

  // Input-level: each native control fully fits its own box (no internal
  // clipping of text, spinners, AM/PM or the calendar indicator). Boxes are
  // captured once and reused for the card-containment check below.
  const boxes = new Map<string, Awaited<ReturnType<Locator["boundingBox"]>>>();
  for (const label of [
    "Participation start",
    "Participation start time",
    "Participation end",
    "Participation end time",
  ]) {
    const input = page.getByLabel(label, { exact: true });
    const box = await input.boundingBox();
    expect(box, `${label} should be rendered`).not.toBeNull();
    boxes.set(label, box);
    expect(
      box!.width,
      `${label} too narrow for native controls`,
    ).toBeGreaterThanOrEqual(
      label.endsWith("time") ? 108 : 128, // 7.5rem time / ~9rem date minus borders
    );
    const clipped = await input.evaluate(
      (el) => el.scrollWidth - el.clientWidth,
    );
    expect(clipped, `${label} clips its native contents`).toBeLessThanOrEqual(
      1,
    );
  }

  // The schedule card is the Surface whose heading is "Participation schedule".
  const scheduleCard = page
    .getByRole("heading", {
      level: 2,
      name: "Participation schedule",
      exact: true,
    })
    .locator("xpath=following-sibling::div[1]");
  await expect(scheduleCard.first()).toBeVisible();
  const cardBox = await scheduleCard.first().boundingBox();
  expect(cardBox, "schedule card should be rendered").not.toBeNull();
  const cardRight = cardBox!.x + cardBox!.width;
  for (const [label, box] of boxes) {
    expect(
      box!.x + box!.width,
      `${label} overflows the schedule card`,
    ).toBeLessThanOrEqual(cardRight + 1);
  }
  return scheduleCard.first();
}

async function fillSchedule(page: Page) {
  const start = new Date("2026-10-16T08:00:00Z");
  const end = new Date("2026-10-16T09:00:00Z");
  const pad = (n: number) => String(n).padStart(2, "0");
  const parts = (d: Date) => ({
    date: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`,
    time: `${pad(d.getHours())}:${pad(d.getMinutes())}`,
  });
  const s = parts(start);
  const e = parts(end);
  await page.getByLabel("Participation start", { exact: true }).fill(s.date);
  await page.getByLabel("Participation start time").fill(s.time);
  await page.getByLabel("Participation end", { exact: true }).fill(e.date);
  await page.getByLabel("Participation end time").fill(e.time);
}

for (const viewport of VIEWPORTS) {
  test.describe(`viewport ${viewport.name}`, () => {
    test.use({
      viewport: { width: viewport.width, height: viewport.height },
    });
    test(`schedule DateTimeField fits without clipping at ${viewport.name}`, async ({
      page,
    }) => {
      await openCreateForm(page);
      await fillSchedule(page);
      const scheduleCard = await assertNoClipping(page);
      await page.screenshot({
        path: `${evidenceDir}/schedule-${viewport.name}-full.png`,
        fullPage: true,
      });
      await scheduleCard.screenshot({
        path: `${evidenceDir}/schedule-${viewport.name}-card.png`,
      });
    });
  });
}

// Locale variants: the context locale changes the page's Intl hour cycle
// (en-US resolves a 12-hour day period, en-GB 24-hour) which the DateTimeField
// timezone label uses. Chromium's native <input type="time"> box geometry is
// locale-invariant in headless builds (the AM/PM segment follows the OS
// locale, not the context locale), so these cases assert the same width-floor
// and no-overflow guarantees that bound the widest native rendering.
for (const locale of ["en-US", "en-GB"]) {
  for (const viewport of [
    { name: "1440x900", width: 1440, height: 900 },
    { name: "390x844", width: 390, height: 844 },
  ]) {
    test.describe(`locale ${locale} at ${viewport.name}`, () => {
      test.use({
        locale,
        viewport: { width: viewport.width, height: viewport.height },
      });
      test(`native time control readable in ${locale} at ${viewport.name}`, async ({
        page,
      }) => {
        await openCreateForm(page);
        await fillSchedule(page);
        const scheduleCard = await assertNoClipping(page);
        await scheduleCard.screenshot({
          path: `${evidenceDir}/schedule-${viewport.name}-${locale}-card.png`,
        });
      });
    });
  }
}
