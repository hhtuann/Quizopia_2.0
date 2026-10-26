import { expect, test, type Page, type Route } from "@playwright/test";
import type {
  Answer,
  AttemptView,
  Result,
  StudentView,
} from "../../features/attempts/api/attempt-contract.generated";
import type { Notification } from "../../features/notifications/model/notification";

test.use({ trace: "off", screenshot: "off", video: "off" });

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
const teacherId = "6ad4c564-3c27-4e6d-91aa-a004334aa8f9";
const classPublicationId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const publicPublicationId = "9ad4c564-3c27-4e6d-91aa-a004334aa8f7";
const classAttemptId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const publicAttemptId = "f7b14962-3a2e-4d2d-926a-3b36ea90c198";
const foreignAttemptId = "07b14962-3a2e-4d2d-926a-3b36ea90c197";
const proctoringSessionId = "d7b14962-3a2e-4d2d-926a-3b36ea90c190";
const notificationId = "17b14962-3a2e-4d2d-926a-3b36ea90c191";
const resultId = "27b14962-3a2e-4d2d-926a-3b36ea90c192";

const classStudentView: StudentView = {
  publicationId: classPublicationId,
  title: "Class Assessment with Monitored Proctoring",
  description: "Bounded class assessment with camera & activity collection",
  audience: "CLASS",
  startAt: "2026-10-16T08:00:00Z",
  endAt: "2099-10-16T12:00:00Z",
  durationMinutes: 60,
  monitoringRequired: true,
  windowStatus: "AVAILABLE",
};

const classAttempt: AttemptView = {
  attemptId: classAttemptId,
  publicationId: classPublicationId,
  ordinal: "1",
  state: "ACTIVE",
  startedAt: "2026-10-16T08:01:00Z",
  deadlineAt: "2099-10-16T09:01:00Z",
  durationMinutes: 60,
  revision: "0",
  serverNow: "2026-10-16T08:02:00Z",
  questions: [
    {
      number: 1,
      type: "SINGLE_CHOICE",
      stemMarkdown: "Choose option Alpha.",
      options: [
        { label: "A", markdown: "Alpha" },
        { label: "B", markdown: "Beta" },
      ],
    },
    {
      number: 2,
      type: "NUMERIC_FILL",
      stemMarkdown: "Enter numeric value 42.00.",
      options: [],
    },
  ],
  answers: [
    { questionNumber: 1, answer: { type: "SINGLE_CHOICE", optionLabel: null } },
    { questionNumber: 2, answer: { type: "NUMERIC_FILL", value: null } },
  ],
  closure: null,
};

const publicAttempt: AttemptView = {
  ...classAttempt,
  attemptId: publicAttemptId,
  publicationId: publicPublicationId,
};

const proctoringSession = {
  id: proctoringSessionId,
  attemptId: classAttemptId,
  publicationId: classPublicationId,
  classroomId: "ca74c564-3c27-4e6d-91aa-a004334aa8f1",
  studentId,
  deadlineAt: "2099-10-16T09:01:00Z",
  status: "ACTIVE",
  createdAt: "2026-10-16T08:01:05Z",
  endedAt: null,
};

const attemptResult: Result = {
  resultId,
  attemptId: classAttemptId,
  publicationId: classPublicationId,
  ordinal: "1",
  earnedWeight: "2.00",
  possibleWeight: "2.00",
  percentageDisplay: "100.00",
  gradingPolicyVersion: 1,
  gradingPolicyId: "QUIZOPIA_GRADING_V1",
  terminalReason: "MANUAL_SUBMIT",
  finalizedAt: "2026-10-16T08:30:00Z",
};

async function mockAuth(page: Page, roles: string[], userId = studentId) {
  await page.route("**/api/auth/refresh", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        accessToken: "w3-final-integration-token",
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
        id: userId,
        username: roles.includes("TEACHER") ? "teacher-user" : "student-user",
        email: "test@quizopia.test",
        roles,
      }),
    }),
  );
}

function json(route: Route, value: unknown, status = 200) {
  return route.fulfill({
    contentType: "application/json",
    status,
    headers: { "cache-control": "no-store" },
    body: JSON.stringify(value),
  });
}

test.describe("W3 Final Frontend Integration Journeys", () => {
  test("Authorized CLASS Attempt activates Proctoring evidence and camera, emits question interactions, and saves answers independently", async ({
    page,
  }) => {
    await mockAuth(page, ["STUDENT"]);
    let currentAttempt = structuredClone(classAttempt);
    const proctoringEvents: Array<{ type: string; questionNumber?: number }> =
      [];
    const proctoringRequests: string[] = [];
    const assessmentSaves: Array<{ questionNumber: number; answer: Answer }> =
      [];
    let ordinal = 1;

    await page.route("**/api/assessments/**", async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      if (path === `/api/assessments/attempts/${classAttemptId}`) {
        return json(route, currentAttempt);
      }
      if (
        path === `/api/assessments/publications/${classPublicationId}/entry`
      ) {
        return json(route, classStudentView);
      }
      const answerMatch = path.match(/\/questions\/(\d+)\/answer$/);
      if (answerMatch) {
        const questionNumber = Number(answerMatch[1]);
        const body = request.postDataJSON() as {
          answer: Answer;
          expectedRevision: string;
        };
        assessmentSaves.push({ questionNumber, answer: body.answer });
        const revision = String(BigInt(currentAttempt.revision) + BigInt(1));
        currentAttempt = {
          ...currentAttempt,
          revision,
          answers: currentAttempt.answers.map((entry) =>
            entry.questionNumber === questionNumber
              ? { ...entry, answer: body.answer }
              : entry,
          ),
        };
        return json(route, {
          attemptId: classAttemptId,
          questionNumber,
          revision,
          savedAt: "2026-10-16T08:10:00Z",
        });
      }
      return route.abort();
    });

    await page.route("**/api/proctoring/**", async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      proctoringRequests.push(`${request.method()} ${path}`);
      if (path === "/api/proctoring/monitoring/sessions") {
        expect(request.postDataJSON()).toEqual({ attemptId: classAttemptId });
        return json(route, proctoringSession, 201);
      }
      if (
        path ===
        `/api/proctoring/monitoring/sessions/${proctoringSessionId}/events`
      ) {
        const body = request.postDataJSON() as {
          type: string;
          questionNumber?: number;
          eventId: string;
        };
        proctoringEvents.push({
          type: body.type,
          questionNumber: body.questionNumber,
        });
        return json(route, {
          id: body.eventId,
          ordinal: ordinal++,
          receivedAt: "2026-10-16T08:05:00Z",
        });
      }
      if (
        path ===
        `/api/proctoring/monitoring/sessions/${proctoringSessionId}/reconcile`
      ) {
        return json(route, {
          acknowledgedUntilOrdinal: ordinal - 1,
          reconciledAt: "2026-10-16T08:05:00Z",
        });
      }
      return route.abort();
    });

    await page.goto(`/app/attempts/${classAttemptId}`);

    // Verify monitoring active banner and camera controls
    await expect(page.getByText("Activity monitoring active")).toBeVisible();
    await expect(
      page.getByText(
        /Fresh server authorization confirmed this bounded CLASS Attempt/,
      ),
    ).toBeVisible();
    await expect(page.getByText("Student camera")).toBeVisible();
    await expect(
      page.getByRole("heading", { name: "Share your camera deliberately" }),
    ).toBeVisible();

    // Verify initial QUESTION_VIEWED event
    await expect
      .poll(() =>
        proctoringEvents.some(
          (e) => e.type === "QUESTION_VIEWED" && e.questionNumber === 1,
        ),
      )
      .toBe(true);

    // Select Option A -> triggers ANSWER_SELECTED and Assessment save
    const radioA = page.getByRole("radio", { name: /A.*Alpha/ });
    await radioA.check();
    await expect(radioA).toBeChecked();

    // Proctoring receives answer evidence metadata
    await expect
      .poll(() =>
        proctoringEvents.some(
          (e) => e.type === "ANSWER_SELECTED" && e.questionNumber === 1,
        ),
      )
      .toBe(true);

    // Assessment receives question save independently
    await expect.poll(() => assessmentSaves.length).toBeGreaterThanOrEqual(1);
    expect(assessmentSaves[0]?.questionNumber).toBe(1);
    expect(assessmentSaves[0]?.answer).toEqual({
      type: "SINGLE_CHOICE",
      optionLabel: "A",
    });

    // Navigate to Question 2 -> emits QUESTION_LEFT (1) and QUESTION_VIEWED (2)
    await page.getByRole("button", { name: /Question 2/ }).click();
    await expect
      .poll(() =>
        proctoringEvents.some(
          (e) => e.type === "QUESTION_LEFT" && e.questionNumber === 1,
        ),
      )
      .toBe(true);
    await expect
      .poll(() =>
        proctoringEvents.some(
          (e) => e.type === "QUESTION_VIEWED" && e.questionNumber === 2,
        ),
      )
      .toBe(true);
  });

  test("PUBLIC Attempt fails closed and never calls Proctoring or mounts camera", async ({
    page,
  }) => {
    await mockAuth(page, ["STUDENT"]);
    const proctoringCalls: string[] = [];

    await page.route("**/api/assessments/**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path === `/api/assessments/attempts/${publicAttemptId}`) {
        return json(route, publicAttempt);
      }
      if (
        path === `/api/assessments/publications/${publicPublicationId}/entry`
      ) {
        // Public publication returns 404 on class entry
        return json(route, { code: "PUBLICATION_NOT_FOUND" }, 404);
      }
      return route.abort();
    });

    await page.route("**/api/proctoring/**", async (route) => {
      proctoringCalls.push(route.request().url());
      return route.abort();
    });

    await page.goto(`/app/attempts/${publicAttemptId}`);

    // Verify assessment questions are rendered normally
    await expect(
      page.getByRole("heading", { name: "Choose option Alpha." }),
    ).toBeVisible();

    // Verify no proctoring alert or camera widget exists
    await expect(page.getByText("Activity monitoring active")).toHaveCount(0);
    await expect(
      page.getByRole("heading", { name: "Student camera" }),
    ).toHaveCount(0);

    // Zero proctoring requests were made
    expect(proctoringCalls).toHaveLength(0);
  });

  test("Proctoring dependency 503 displays authority unavailable alert without disrupting assessment", async ({
    page,
  }) => {
    await mockAuth(page, ["STUDENT"]);

    await page.route("**/api/assessments/**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path === `/api/assessments/attempts/${classAttemptId}`) {
        return json(route, classAttempt);
      }
      if (
        path === `/api/assessments/publications/${classPublicationId}/entry`
      ) {
        return json(route, classStudentView);
      }
      return route.abort();
    });

    await page.route("**/api/proctoring/monitoring/sessions", (route) =>
      json(route, { code: "SERVICE_UNAVAILABLE" }, 503),
    );

    await page.goto(`/app/attempts/${classAttemptId}`);

    // Verify informative unavailable message
    await expect(page.getByText("Monitoring unavailable")).toBeVisible();
    await expect(
      page.getByText(
        /No activity observer or camera is active. This does not change answers/,
      ),
    ).toBeVisible();

    // Assessment is still interactive
    await expect(page.getByRole("radio", { name: /A.*Alpha/ })).toBeEnabled();
  });

  test("Student Notification links to the real authorized Attempt route and loads attempt", async ({
    page,
  }) => {
    await mockAuth(page, ["STUDENT"]);
    const notification: Notification = {
      notificationId,
      publicationId: classPublicationId,
      publicationTitle: "Class Assessment Notice",
      attemptId: classAttemptId,
      closureRequestId: "37b14962-3a2e-4d2d-926a-3b36ea90c193",
      teacherInitiated: true,
      contentVersion: 1,
      publishedAt: "2026-10-14T09:00:00Z",
      confirmedNoticeAt: "2026-10-14T09:30:00Z",
      closureCutoffAt: "2026-10-16T09:30:00Z",
      warningHours: 48,
      noticeStatus: "NOTICE_CONFIRMED",
      message: "Confirmed notice for your active attempt.",
      resultId: null,
      serverNow: "2026-10-14T10:00:00Z",
    };

    await page.route("**/api/assessments/**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path === `/api/assessments/notifications/${notificationId}`) {
        return json(route, notification);
      }
      if (path === `/api/assessments/attempts/${classAttemptId}`) {
        return json(route, classAttempt);
      }
      if (
        path === `/api/assessments/publications/${classPublicationId}/entry`
      ) {
        return json(route, classStudentView);
      }
      return route.abort();
    });

    await page.route("**/api/proctoring/**", async (route) =>
      json(route, proctoringSession, 201),
    );

    await page.goto(`/app/notifications/${notificationId}`);

    // Verify the attempt link exists and points to /app/attempts/${classAttemptId}
    const attemptLink = page.getByRole("link", { name: classAttemptId });
    await expect(attemptLink).toBeVisible();
    await expect(attemptLink).toHaveAttribute(
      "href",
      `/app/attempts/${classAttemptId}`,
    );

    // Click the link and verify navigation to real Attempt page
    await attemptLink.click();
    await expect(page).toHaveURL(`/app/attempts/${classAttemptId}`);
    await expect(
      page.getByRole("heading", { name: "Choose option Alpha." }),
    ).toBeVisible();
  });

  test("Teacher Results links to authorized per-Attempt monitoring and masks unauthorized attempt", async ({
    page,
  }) => {
    await mockAuth(page, ["TEACHER"], teacherId);

    await page.route(
      `**/api/assessments/publications/${classPublicationId}/results**`,
      (route) =>
        json(route, {
          items: [
            {
              attemptId: classAttemptId,
              studentId,
              ordinal: "1",
              state: "ACTIVE",
              result: null,
            },
            {
              attemptId: foreignAttemptId,
              studentId: "99999999-9999-4999-8999-999999999999",
              ordinal: "2",
              state: "ACTIVE",
              result: null,
            },
          ],
          nextCursor: null,
        }),
    );

    let monitoringLookups: string[] = [];
    await page.route(
      "**/api/proctoring/monitoring/sessions/**",
      async (route) => {
        const path = new URL(route.request().url()).pathname;
        monitoringLookups.push(path);
        if (path.endsWith(`/by-attempt/${classAttemptId}`)) {
          return json(route, proctoringSession);
        }
        if (path.endsWith(`/by-attempt/${foreignAttemptId}`)) {
          return json(route, { code: "PROCTORING_NOT_ACCESSIBLE" }, 404);
        }
        if (path.endsWith(`/events`)) {
          return json(route, { items: [], nextAfter: null });
        }
        return route.abort();
      },
    );

    await page.goto(`/app/assessments/${classPublicationId}/results`);

    // Verify monitoring link exists for both attempts
    const classAttemptLink = page.getByRole("link", {
      name: `Open monitoring for Attempt ${classAttemptId}`,
    });
    const foreignAttemptLink = page.getByRole("link", {
      name: `Open monitoring for Attempt ${foreignAttemptId}`,
    });
    await expect(classAttemptLink).toHaveAttribute(
      "href",
      `/app/monitoring/teacher/attempts/${classAttemptId}`,
    );
    await expect(foreignAttemptLink).toHaveAttribute(
      "href",
      `/app/monitoring/teacher/attempts/${foreignAttemptId}`,
    );

    // Click authorized attempt monitoring link
    await classAttemptLink.click();
    await expect(page).toHaveURL(
      `/app/monitoring/teacher/attempts/${classAttemptId}`,
    );
    await expect(
      page.getByRole("heading", { name: "Authorized Attempt monitoring" }),
    ).toBeVisible();
    await expect(
      page.getByRole("heading", { name: "Activity timeline" }),
    ).toBeVisible();

    // Visit foreign attempt monitoring directly
    await page.goto(`/app/monitoring/teacher/attempts/${foreignAttemptId}`);
    await expect(page.getByText("Session not accessible")).toBeVisible();
    await expect(
      page.getByText(/does not reveal whether a foreign resource exists/),
    ).toBeVisible();
  });
});
