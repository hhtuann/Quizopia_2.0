import { expect, test, type Page } from "@playwright/test";

// These journeys assert the English UI. The application defaults to Vietnamese;
// pin the preference cookie before any navigation so assertions stay stable.
test.beforeEach(async ({ page }) => {
  await page
    .context()
    .addCookies([
      { name: "quizopia-locale", value: "en", url: "http://localhost:3000" },
    ]);
});

// T2 global UX consistency visual evidence: captures representative Teacher
// and Student screens at desktop (1920x1080, 1440x900), tablet (768x1024) and
// mobile (390x844) sizes, asserting no viewport-level horizontal overflow
// alongside each screenshot. Screenshots are written to EVIDENCE_DIR when set
// (otherwise the default test-results/visual output directory).
const evidenceDir = process.env.EVIDENCE_DIR ?? "test-results/visual-t2";

const VIEWPORTS = [
  { name: "1920x1080", width: 1920, height: 1080 },
  { name: "1440x900", width: 1440, height: 900 },
  { name: "768x1024", width: 768, height: 1024 },
  { name: "390x844", width: 390, height: 844 },
] as const;

const teacher = {
  email: "teacher01@gmail.com",
  id: "a7a4c564-3c27-4e6d-91aa-a004334aa8f9",
  roles: ["TEACHER"],
  username: "teacher01",
};
const student = {
  email: "learner01@gmail.com",
  id: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
  roles: ["STUDENT"],
  username: "learner01",
};

const quizId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const quizVersionId = "9bd4c564-3c27-4e6d-91aa-a004334aa8f7";
const publicationId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const notificationId = "5d1b1496-3a2e-4d2d-926a-3b36ea90c1aa";
const attemptId = "2f2b1496-3a2e-4d2d-926a-3b36ea90c1bb";

async function mockAuth(page: Page, user: typeof teacher) {
  await page.route("**/api/auth/refresh", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        accessToken: "t2-visual-token",
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

function mockQuizLibrary(page: Page) {
  return page.route(`**/api/quizzes`, (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        items: [1, 2, 3].map((n) => ({
          quizId: `8ad4c564-3c27-4e6d-91aa-a004334aa8f${n}`,
          title: `Corporate Trust quiz ${n}`,
          description: `Representative quiz ${n} for visual evidence.`,
          createdAt: "2026-10-16T07:00:00Z",
          updatedAt: "2026-10-16T07:00:00Z",
          latestVersionNumber: n,
        })),
        nextCursor: null,
      }),
    }),
  );
}

function mockOpenPublication(page: Page) {
  return page.route(
    `**/api/assessments/publications/${publicationId}`,
    (route) =>
      route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify({
          publicationId,
          configuration: {
            title: "Pinned version assessment",
            description: "Version three",
            quizId,
            quizVersionId,
            versionNumber: 3,
            audience: "PUBLIC",
            classroomId: null,
            startAt: "2026-10-16T08:00:00.000Z",
            endAt: "2026-10-16T09:00:00.000Z",
            durationMinutes: 40,
            monitoringChoice: "DISABLED",
            maxAttempts: 1,
          },
          state: "OPEN",
          revision: 1,
          createdAt: "2026-10-16T07:00:00Z",
          openedAt: "2026-10-16T07:30:00Z",
          privateLocator: "P".repeat(43),
        }),
      }),
  );
}

function mockNotifications(page: Page) {
  return page.route("**/api/assessments/notifications", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        items: [
          {
            notificationId,
            publicationId,
            publicationTitle: "Closure protocol assessment",
            attemptId,
            closureRequestId: "c1b14962-3a2e-4d2d-926a-3b36ea90c1cc",
            teacherInitiated: true,
            contentVersion: 1,
            publishedAt: "2026-10-14T09:00:00.000000Z",
            confirmedNoticeAt: "2026-10-14T09:30:00.123456Z",
            closureCutoffAt: "2026-10-16T09:30:00.123456Z",
            warningHours: 48,
            noticeStatus: "NOTICE_CONFIRMED",
            message:
              "Your teacher requested delayed closure of this assessment. The confirmed cutoff below is authoritative.",
            resultId: null,
            serverNow: "2026-10-14T10:30:00.123456Z",
          },
        ],
        nextCursor: null,
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
        sourceSnapshot: "Câu 1 [NUMERIC_FILL]: Source?\nĐáp án: 42",
        structuredContent: {
          questions: [
            {
              number: 1,
              type: "NUMERIC_FILL",
              stemMarkdown: "Source?",
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

async function capture(page: Page, name: string, anchor: () => Promise<void>) {
  await anchor();
  expect(
    await page.evaluate(
      () =>
        document.documentElement.scrollWidth -
        document.documentElement.clientWidth,
    ),
    `${name}: viewport-level horizontal overflow`,
  ).toBeLessThanOrEqual(1);
  await page.screenshot({
    path: `${evidenceDir}/${name}.png`,
    fullPage: true,
  });
}

test.describe("T2 visual evidence", () => {
  // Serial: several SPA navigations per test contend for the shared dev
  // server's on-demand compilation on developer machines.
  test.describe.configure({ mode: "serial" });
  test.setTimeout(90_000);

  for (const viewport of VIEWPORTS) {
    test.describe(`viewport ${viewport.name}`, () => {
      test.use({
        viewport: { width: viewport.width, height: viewport.height },
      });

      test(`teacher screens at ${viewport.name}`, async ({ page }) => {
        await mockAuth(page, teacher);
        await mockQuizLibrary(page);
        await mockOpenPublication(page);
        await mockQuizVersion(page);

        await page.goto("/app");
        await capture(page, `teacher-home-${viewport.name}`, async () => {
          await expect(
            page.getByRole("heading", { name: "Your Quizopia workspace" }),
          ).toBeVisible();
        });

        await page.goto("/app/quizzes");
        await capture(page, `quiz-library-${viewport.name}`, async () => {
          await expect(
            page.getByRole("link", { name: "Corporate Trust quiz 1" }),
          ).toBeVisible();
        });

        await page.goto(
          `/app/assessments/new?quizId=${quizId}&quizVersionId=${quizVersionId}&versionNumber=3`,
        );
        await capture(page, `assessment-create-${viewport.name}`, async () => {
          await page
            .getByRole("radio", { name: "Public", exact: true })
            .check();
          await expect(
            page.getByLabel("Participation start", { exact: true }),
          ).toBeVisible();
        });

        await page.goto(`/app/assessments/${publicationId}`);
        await capture(page, `publication-detail-${viewport.name}`, async () => {
          await expect(
            page.getByRole("heading", { name: "Pinned version assessment" }),
          ).toBeVisible();
        });
      });

      test(`student screens at ${viewport.name}`, async ({ page }) => {
        await mockAuth(page, student);
        await mockNotifications(page);

        await page.goto("/app");
        await capture(page, `student-home-${viewport.name}`, async () => {
          await expect(
            page.getByRole("heading", { name: "Your Quizopia workspace" }),
          ).toBeVisible();
        });

        await page.goto("/app/assessments/join");
        await capture(page, `student-join-${viewport.name}`, async () => {
          await expect(
            page.getByRole("heading", { name: "Enter an assessment" }),
          ).toBeVisible();
        });

        await page.goto("/app/notifications");
        await capture(
          page,
          `notifications-inbox-${viewport.name}`,
          async () => {
            await expect(
              page.getByRole("heading", {
                name: "Closure protocol assessment",
              }),
            ).toBeVisible();
          },
        );
      });
    });
  }

  test("authentication screen at 1440x900 and 390x844", async ({ page }) => {
    for (const viewport of [
      { name: "1440x900", width: 1440, height: 900 },
      { name: "390x844", width: 390, height: 844 },
    ]) {
      await page.setViewportSize({
        width: viewport.width,
        height: viewport.height,
      });
      await page.goto("/login");
      await capture(page, `login-${viewport.name}`, async () => {
        await expect(
          page.getByRole("heading", { name: /sign in/i }),
        ).toBeVisible();
      });
    }
  });
});
