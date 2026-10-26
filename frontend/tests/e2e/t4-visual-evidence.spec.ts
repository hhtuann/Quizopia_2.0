import { expect, test, type Page } from "@playwright/test";

// T4 visual evidence: representative Teacher and Student screens captured in
// BOTH locales (vi default, en via preference cookie) at desktop, tablet and
// mobile sizes, each with a no-horizontal-overflow assertion. Screenshots go
// to EVIDENCE_DIR when set (otherwise test-results/visual-t4).
const evidenceDir = process.env.EVIDENCE_DIR ?? "test-results/visual-t4";

const VIEWPORTS = [
  { name: "1920x1080", width: 1920, height: 1080 },
  { name: "1440x900", width: 1440, height: 900 },
  { name: "768x1024", width: 768, height: 1024 },
  { name: "390x844", width: 390, height: 844 },
] as const;
const LOCALES = [
  { name: "vi", cookie: false },
  { name: "en", cookie: true },
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

async function mockAuth(page: Page, user: typeof teacher) {
  await page.route("**/api/auth/refresh", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        accessToken: "t4-visual-token",
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
  await page.screenshot({ path: `${evidenceDir}/${name}.png`, fullPage: true });
}

test.describe("T4 visual evidence", () => {
  test.describe.configure({ mode: "serial" });
  test.setTimeout(120_000);

  for (const locale of LOCALES) {
    for (const viewport of VIEWPORTS) {
      test.describe(`${locale.name} ${viewport.name}`, () => {
        test.use({
          viewport: { width: viewport.width, height: viewport.height },
        });

        test(`teacher screens ${locale.name} at ${viewport.name}`, async ({
          page,
        }) => {
          if (locale.cookie) {
            await page.context().addCookies([
              {
                name: "quizopia-locale",
                value: "en",
                url: "http://localhost:3000",
              },
            ]);
          }
          await mockAuth(page, teacher);
          await page.route("**/api/assessments/publications**", (route) =>
            route.fulfill({
              contentType: "application/json",
              status: 200,
              body: JSON.stringify({
                items: [
                  {
                    publicationId,
                    title:
                      locale.name === "vi"
                        ? "Bài kiểm tra giữa kỳ"
                        : "Midterm assessment",
                    audience: "CLASS",
                    state: "OPEN",
                    startAt: "2026-10-16T08:00:00.000Z",
                    endAt: "2026-10-16T09:00:00.000Z",
                    createdAt: "2026-10-15T07:00:00.000Z",
                    revision: 2,
                    quizId,
                    quizVersionId,
                    versionNumber: 3,
                  },
                ],
                nextCursor: null,
              }),
            }),
          );
          await page.route("**/api/quizzes/*/versions/3", (route) =>
            route.fulfill({
              contentType: "application/json",
              status: 200,
              body: JSON.stringify({
                id: quizVersionId,
                quizId,
                versionNumber: 3,
                titleSnapshot:
                  locale.name === "vi" ? "Bài kiểm tra giữa kỳ" : "Midterm",
                descriptionSnapshot: null,
                contentSchemaVersion: 1,
                createdAt: "2026-10-15T07:00:00Z",
                sourceSnapshot: "Câu 1 [NUMERIC_FILL]: 2+2?\nĐáp án: 4.00",
                structuredContent: {
                  questions: [
                    {
                      number: 1,
                      type: "NUMERIC_FILL",
                      stemMarkdown: "2+2?",
                      options: [],
                      numericAnswer: "4.00",
                      explanationMarkdown: null,
                    },
                  ],
                },
              }),
            }),
          );
          await page.route("**/api/classrooms**", (route) =>
            route.fulfill({
              contentType: "application/json",
              status: 200,
              body: JSON.stringify({
                items: [
                  {
                    id: "c1b14962-3a2e-4d2d-926a-3b36ea90c1cc",
                    name: locale.name === "vi" ? "Lớp 10A" : "Grade 10A",
                    subject: "Physics",
                    grade: "10",
                    description: null,
                    createdAt: "2026-10-01T07:00:00Z",
                  },
                ],
                nextCursor: null,
              }),
            }),
          );

          await page.goto("/app");
          await capture(
            page,
            `teacher-home-${locale.name}-${viewport.name}`,
            async () => {
              await expect(
                page.getByRole("heading", {
                  name:
                    locale.name === "vi" ? /Quizopia/ : /Quizopia workspace/,
                }),
              ).toBeVisible();
              await expect(page.locator("html")).toHaveAttribute(
                "lang",
                locale.name,
              );
            },
          );

          await page.goto("/app/assessments");
          await capture(
            page,
            `my-assessments-${locale.name}-${viewport.name}`,
            async () => {
              await expect(
                page.getByRole("heading", {
                  name:
                    locale.name === "vi"
                      ? /Bài kiểm tra của tôi/
                      : /My Assessments/,
                }),
              ).toBeVisible();
            },
          );

          await page.goto(
            `/app/assessments/new?quizId=${quizId}&quizVersionId=${quizVersionId}&versionNumber=3`,
          );
          await capture(
            page,
            `create-form-${locale.name}-${viewport.name}`,
            async () => {
              await page
                .getByRole("radio", {
                  name: locale.name === "vi" ? /Lớp học/ : "Classroom",
                })
                .check();
            },
          );
        });

        test(`student and auth screens ${locale.name} at ${viewport.name}`, async ({
          page,
        }) => {
          if (locale.cookie) {
            await page.context().addCookies([
              {
                name: "quizopia-locale",
                value: "en",
                url: "http://localhost:3000",
              },
            ]);
          }
          await mockAuth(page, student);

          await page.goto("/login");
          await capture(
            page,
            `login-${locale.name}-${viewport.name}`,
            async () => {
              await expect(
                page.getByRole("heading", {
                  name: locale.name === "vi" ? "Đăng nhập" : "Sign in",
                  exact: true,
                }),
              ).toBeVisible();
            },
          );

          await page.goto("/app/assessments/join");
          await capture(
            page,
            `student-join-${locale.name}-${viewport.name}`,
            async () => {
              await expect(
                page.getByRole("heading", {
                  name:
                    locale.name === "vi"
                      ? /Vào bài kiểm tra/
                      : "Enter an assessment",
                }),
              ).toBeVisible();
            },
          );
        });
      });
    }
  }
});
