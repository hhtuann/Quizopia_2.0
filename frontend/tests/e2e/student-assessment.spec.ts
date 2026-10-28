import { expect, test, type Page, type Route } from "@playwright/test";
import type {
  Answer,
  AttemptView,
  Result,
  StudentView,
} from "../../features/attempts/api/attempt-contract.generated";

// Browser contract tests: production UI/executor with intercepted Gateway traffic.
// They do not establish real Assessment/Identity/Classroom/Redis compatibility.
// These journeys assert English UI strings while the app defaults to
// Vietnamese, so pin the locale cookie before any navigation (SSR reads it).
test.beforeEach(async ({ page }) => {
  await page
    .context()
    .addCookies([
      { name: "quizopia-locale", value: "en", url: "http://localhost:3000" },
    ]);
});

const studentId = "3ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const publicationId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const attemptId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const resultId = "9bd4c564-3c27-4e6d-91aa-a004334aa8f7";
const locator = "L".repeat(43);
const preview: StudentView = {
  publicationId,
  title: "Four-format browser assessment",
  description: "Intercepted contract coverage",
  audience: "PUBLIC",
  startAt: "2099-10-16T08:00:00Z",
  endAt: "2099-10-16T10:00:00Z",
  durationMinutes: 40,
  monitoringRequired: false,
  windowStatus: "AVAILABLE",
};
const initialAttempt: AttemptView = {
  attemptId,
  publicationId,
  ordinal: "1",
  state: "ACTIVE",
  startedAt: "2099-10-16T08:01:00Z",
  deadlineAt: "2099-10-16T08:41:00Z",
  durationMinutes: 40,
  revision: "0",
  serverNow: "2099-10-16T08:02:00Z",
  questions: [
    {
      number: 1,
      type: "SINGLE_CHOICE",
      stemMarkdown: "Choose alpha.",
      options: [
        { label: "A", markdown: "Alpha" },
        { label: "B", markdown: "Beta" },
      ],
    },
    {
      number: 2,
      type: "MULTIPLE_CHOICE",
      stemMarkdown: "Choose A and C.",
      options: [
        { label: "A", markdown: "First" },
        { label: "B", markdown: "Second" },
        { label: "C", markdown: "Third" },
      ],
    },
    {
      number: 3,
      type: "TRUE_FALSE_MATRIX",
      stemMarkdown: "Judge every statement.",
      options: [
        { label: "A", markdown: "Statement A" },
        { label: "B", markdown: "Statement B" },
        { label: "C", markdown: "Statement C" },
        { label: "D", markdown: "Statement D" },
      ],
    },
    {
      number: 4,
      type: "NUMERIC_FILL",
      stemMarkdown: "Enter the exact numeric token.",
      options: [],
    },
  ],
  answers: [
    { questionNumber: 1, answer: { type: "SINGLE_CHOICE", optionLabel: null } },
    {
      questionNumber: 2,
      answer: { type: "MULTIPLE_CHOICE", optionLabels: [] },
    },
    {
      questionNumber: 3,
      answer: {
        type: "TRUE_FALSE_MATRIX",
        judgments: { A: null, B: null, C: null, D: null },
      },
    },
    { questionNumber: 4, answer: { type: "NUMERIC_FILL", value: null } },
  ],
  closure: null,
};
const result: Result = {
  resultId,
  attemptId,
  publicationId,
  ordinal: "1",
  earnedWeight: "3.25",
  possibleWeight: "4",
  percentageDisplay: "81.25",
  gradingPolicyVersion: 1,
  gradingPolicyId: "QUIZOPIA_GRADING_V1",
  terminalReason: "MANUAL_SUBMIT",
  finalizedAt: "2099-10-16T08:20:00Z",
};

async function mockStudentAuth(page: Page) {
  await page.route("**/api/auth/refresh", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        accessToken: "student-browser-access",
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
        id: studentId,
        username: "student",
        email: "student@gmail.com",
        roles: ["STUDENT"],
      }),
    }),
  );
}

function fulfill(route: Route, status: number, value: unknown) {
  return route.fulfill({
    contentType: "application/json",
    headers: { "cache-control": "no-store" },
    status,
    body: JSON.stringify(value),
  });
}

async function mockAssessment(page: Page) {
  let current = structuredClone(initialAttempt);
  const saves: Array<{
    readonly answer: Answer;
    readonly expectedRevision: string;
    readonly key: string | undefined;
    readonly questionNumber: number;
  }> = [];
  let starts = 0;
  let gets = 0;
  let submittedBody: unknown = null;
  await page.route("**/api/assessments/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    expect(request.headers().authorization).toBe(
      "Bearer student-browser-access",
    );
    expect(request.url()).not.toContain(locator);
    if (path === "/api/assessments/publications/entry") {
      expect(request.postDataJSON()).toEqual({ locator });
      await fulfill(route, 200, preview);
      return;
    }
    if (path === `/api/assessments/publications/${publicationId}/entry`) {
      await fulfill(route, 404, { code: "PUBLICATION_NOT_FOUND" });
      return;
    }
    if (path.endsWith(`/publications/${publicationId}/attempts/start`)) {
      starts += 1;
      expect(request.postDataJSON()).toEqual({ locator });
      expect(request.headers()["idempotency-key"]).toMatch(
        /^[A-Za-z0-9_-]{16,128}$/,
      );
      await fulfill(route, 201, current);
      return;
    }
    if (path === `/api/assessments/attempts/${attemptId}`) {
      gets += 1;
      await fulfill(route, 200, current);
      return;
    }
    const answerMatch = path.match(/\/questions\/(\d+)\/answer$/);
    if (answerMatch) {
      const questionNumber = Number(answerMatch[1]);
      const body = request.postDataJSON() as {
        answer: Answer;
        expectedRevision: string;
      };
      expect(body.expectedRevision).toBe(current.revision);
      saves.push({
        ...body,
        questionNumber,
        key: request.headers()["idempotency-key"],
      });
      const revision = String(BigInt(current.revision) + BigInt(1));
      current = {
        ...current,
        revision,
        answers: current.answers.map((entry) =>
          entry.questionNumber === questionNumber
            ? { ...entry, answer: body.answer }
            : entry,
        ),
      };
      await fulfill(route, 200, {
        attemptId,
        questionNumber,
        revision,
        savedAt: "2099-10-16T08:10:00Z",
      });
      return;
    }
    if (path.endsWith(`/attempts/${attemptId}/submit`)) {
      submittedBody = request.postDataJSON();
      expect(request.headers()["idempotency-key"]).toMatch(
        /^[A-Za-z0-9_-]{16,128}$/,
      );
      current = { ...current, state: "SUBMITTED" };
      await fulfill(route, 200, result);
      return;
    }
    if (path.endsWith(`/attempts/${attemptId}/result`)) {
      await fulfill(route, 200, result);
      return;
    }
    if (path.endsWith(`/publications/${publicationId}/aggregate-score`)) {
      await fulfill(route, 200, { publicationId, bestResult: result });
      return;
    }
    if (path.endsWith(`/attempts/${attemptId}/review`)) {
      await fulfill(route, 409, { code: "REVIEW_NOT_AVAILABLE" });
      return;
    }
    throw new Error(
      `Unexpected assessment request: ${request.method()} ${path}`,
    );
  });
  return {
    read: () => ({ current, gets, saves, starts, submittedBody }),
  };
}

test("PUBLIC entry, all four answer types, serialized autosave and exact result complete through intercepted Gateway", async ({
  page,
}) => {
  await mockStudentAuth(page);
  const assessment = await mockAssessment(page);
  await page.goto("/app/assessments/join");
  await page.getByLabel("43-character private locator").fill(locator);
  await page.getByRole("button", { name: "Check access" }).click();
  await expect(
    page.getByRole("heading", { name: "Four-format browser assessment" }),
  ).toBeVisible();
  expect(page.url()).not.toContain(locator);
  expect(
    await page.evaluate(async () => ({
      indexedDb:
        "databases" in indexedDB ? (await indexedDB.databases()).length : 0,
      localStorage: localStorage.length,
      sessionStorage: sessionStorage.length,
    })),
  ).toMatchObject({ localStorage: 0, sessionStorage: 0 });

  await page.getByRole("button", { name: "Start or resume" }).click();
  await expect(page).toHaveURL(`/app/attempts/${attemptId}`);
  await page.getByRole("radio", { name: /A.*Alpha/ }).check();
  await page.getByRole("button", { name: /Question 2/ }).click();
  await page.getByRole("checkbox", { name: /A.*First/ }).check();
  await page.getByRole("checkbox", { name: /C.*Third/ }).check();
  await page.getByRole("button", { name: /Question 3/ }).click();
  for (const [statement, judgment] of [
    ["Statement A", "True"],
    ["Statement B", "False"],
    ["Statement C", "True"],
    ["Statement D", "Unanswered"],
  ] as const) {
    await page
      .getByText(statement)
      .locator("xpath=ancestor::fieldset[1]")
      .getByRole("radio", { name: judgment })
      .check();
  }
  await page.getByRole("button", { name: /Question 4/ }).click();
  await page.getByLabel("Four-character numeric answer").fill("2.50");
  await expect.poll(() => assessment.read().saves.length).toBe(4);
  expect(
    assessment
      .read()
      .saves.every((save) => /^[A-Za-z0-9_-]{16,128}$/.test(save.key ?? "")),
  ).toBe(true);

  await page.getByRole("button", { name: "Submit attempt" }).click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Submit complete answers" })
    .click();
  await expect(page).toHaveURL(`/app/attempts/${attemptId}/result`);
  await expect(page.getByText("81.25%", { exact: true })).toBeVisible();
  expect(assessment.read().starts).toBe(1);
  expect(assessment.read().submittedBody).toMatchObject({
    expectedRevision: "4",
    answers: expect.arrayContaining([
      {
        questionNumber: 4,
        answer: { type: "NUMERIC_FILL", value: "2.50" },
      },
    ]),
  });
});

test("review stays withheld until durable CLOSED and mobile keyboard layout remains usable", async ({
  page,
}) => {
  await mockStudentAuth(page);
  await mockAssessment(page);
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto(`/app/attempts/${attemptId}/review`);
  await expect(page.getByText("Answer review unavailable")).toBeVisible();
  await expect(page.getByText("Correct answer")).toHaveCount(0);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.keyboard.press("Tab");
  await expect(
    page.getByRole("link", { name: "Skip to main content" }),
  ).toBeFocused();
});

test("offline and visible reconnects fence editing until authoritative status refreshes", async ({
  context,
  page,
}) => {
  await mockStudentAuth(page);
  const assessment = await mockAssessment(page);
  await page.goto(`/app/attempts/${attemptId}`);
  const firstAnswer = page.getByRole("radio", { name: /A.*Alpha/ });
  await expect(firstAnswer).toBeEnabled();
  const authoritativeReadsBeforeReconnect = assessment.read().gets;
  await page.getByRole("button", { name: "Submit attempt" }).click();
  const confirm = page
    .getByRole("dialog")
    .getByRole("button", { name: "Submit complete answers" });
  await expect(confirm).toBeVisible();

  await context.setOffline(true);
  await expect(page.getByText("Status check required")).toBeVisible();
  await expect(firstAnswer).toBeDisabled();
  await confirm.click();
  expect(assessment.read().submittedBody).toBeNull();
  await expect(page.getByText(/Submitting is paused/)).toBeVisible();
  await context.setOffline(false);
  await expect
    .poll(() => assessment.read().gets)
    .toBeGreaterThan(authoritativeReadsBeforeReconnect);
  await expect(firstAnswer).toBeEnabled();
});

test("same-document browser history warns before leaving an unsaved answer", async ({
  page,
}) => {
  await mockStudentAuth(page);
  await mockAssessment(page);
  await page.goto(`/app/attempts/${attemptId}`);
  await page.evaluate(() => history.pushState({}, "", "?history-guard=1"));
  await page.getByRole("radio", { name: /A.*Alpha/ }).check();

  const dialogPromise = page.waitForEvent("dialog");
  const back = page.evaluate(() => history.back());
  const dialog = await dialogPromise;
  expect(dialog.message()).toContain("unsaved or pending answers");
  await dialog.dismiss();
  await back;
  await expect(page.getByRole("radio", { name: /A.*Alpha/ })).toBeChecked();
  await expect(
    page.getByRole("heading", { name: "Student assessment" }),
  ).toBeVisible();
});

test("switching locale mid-attempt preserves unsaved answers and submission still succeeds", async ({
  page,
}) => {
  await mockStudentAuth(page);
  const assessment = await mockAssessment(page);
  await page.goto(`/app/attempts/${attemptId}`);
  await expect(
    page.getByRole("heading", { name: "Student assessment" }),
  ).toBeVisible();

  // Unsaved local work: a typed numeric answer not yet flushed to the server.
  await page.getByRole("button", { name: /Question 4/ }).click();
  await page.getByLabel("Four-character numeric answer").fill("1.00");

  // Switch the application language to Vietnamese through the user menu
  // language options on the authenticated shell.
  await page.getByRole("button", { name: /Open user menu for/ }).click();
  await page.getByRole("menuitemradio", { name: "Tiếng Việt" }).click();
  await page.keyboard.press("Escape");
  await expect(
    page.getByRole("heading", { name: "Bài kiểm tra của học sinh" }),
  ).toBeVisible();
  await expect(page.getByText("Đồng hồ đếm ngược tham khảo")).toBeVisible();

  // No remount occurred: the unsaved input value and selected question survive,
  // and the informative countdown keeps rendering after the locale switch.
  await expect(page.getByLabel("Câu trả lời số gồm bốn ký tự")).toHaveValue(
    "1.00",
  );
  await expect(page.getByRole("timer")).toHaveText(/\d{2}:\d{2}:\d{2}/);

  // Autosave continued in the background; wait for the numeric save.
  await expect
    .poll(() =>
      assessment
        .read()
        .saves.some(
          (save) =>
            save.questionNumber === 4 &&
            save.answer.type === "NUMERIC_FILL" &&
            save.answer.value === "1.00",
        ),
    )
    .toBe(true);

  // Submitting from the Vietnamese UI still completes the authoritative flow.
  await page.getByRole("button", { name: "Nộp bài" }).click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Nộp toàn bộ câu trả lời" })
    .click();
  await expect(page).toHaveURL(`/app/attempts/${attemptId}/result`);
  // Exact decimal display is localized but never re-rounded: 81,25% in vi.
  await expect(page.getByText("81,25%", { exact: true })).toBeVisible();
  expect(assessment.read().submittedBody).toMatchObject({
    answers: expect.arrayContaining([
      {
        questionNumber: 4,
        answer: { type: "NUMERIC_FILL", value: "1.00" },
      },
    ]),
  });
});

test("dependency 503 remains a feature error without logout or refresh cascade", async ({
  page,
}) => {
  let refreshCalls = 0;
  await page.route("**/api/auth/refresh", (route) => {
    refreshCalls += 1;
    return route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        accessToken: "student-browser-access",
        expiresIn: 300,
        tokenType: "Bearer",
      }),
    });
  });
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        id: studentId,
        username: "student",
        email: "student@gmail.com",
        roles: ["STUDENT"],
      }),
    }),
  );
  await page.route("**/api/assessments/publications/entry", (route) =>
    fulfill(route, 503, { code: "DEPENDENCY_UNAVAILABLE" }),
  );
  await page.goto("/app/assessments/join");
  await page.getByLabel("43-character private locator").fill(locator);
  await page.getByRole("button", { name: "Check access" }).click();
  await expect(
    page.getByText(/A required part of Quizopia is unavailable/),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: /Open user menu for student/ }),
  ).toBeVisible();
  expect(refreshCalls).toBe(1);
});
