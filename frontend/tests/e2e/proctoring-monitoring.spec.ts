import { expect, test, type Page, type Route } from "@playwright/test";

// Grant responses contain bearer credentials. Monitoring tests must never
// create trace, screenshot, or video artifacts that could retain them.
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

const attemptId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const sessionId = "f7b14962-3a2e-4d2d-926a-3b36ea90c199";
const session = {
  id: sessionId,
  attemptId,
  publicationId: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
  classroomId: "9bd4c564-3c27-4e6d-91aa-a004334aa8f7",
  studentId: "a7a4c564-3c27-4e6d-91aa-a004334aa8f9",
  deadlineAt: "2099-10-16T12:00:00Z",
  status: "ACTIVE",
  createdAt: "2026-10-16T10:00:00Z",
  endedAt: null,
};

async function mockAuth(page: Page, roles: string[]) {
  await page.route("**/api/auth/refresh", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        accessToken: "monitoring-browser-access",
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
        id: session.studentId,
        username: "monitoring-user",
        email: "monitoring@example.test",
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

test("Student monitoring is deliberate, emits bounded browser facts, and reports denied camera truthfully", async ({
  page,
}) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "mediaDevices", {
      configurable: true,
      value: {
        getUserMedia: async () => {
          (window as Window & { cameraRequests?: number }).cameraRequests =
            ((window as Window & { cameraRequests?: number }).cameraRequests ??
              0) + 1;
          throw new DOMException("denied", "NotAllowedError");
        },
      },
    });
  });
  await mockAuth(page, ["STUDENT"]);
  const requests: Array<{ path: string; body: unknown }> = [];
  let ordinal = 1;
  await page.route("**/api/proctoring/monitoring/sessions**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const body = request.postData() ? request.postDataJSON() : null;
    requests.push({ path, body });
    expect(request.headers().authorization).toBe(
      "Bearer monitoring-browser-access",
    );
    if (path.endsWith("/events")) {
      return json(route, {
        id: body.eventId,
        ordinal: ordinal++,
        receivedAt: "2026-10-16T10:00:01Z",
      });
    }
    return json(route, session);
  });

  await page.goto(`/app/monitoring/attempts/${attemptId}`);
  await expect(
    page.getByRole("heading", { name: "Activity and camera monitoring" }),
  ).toBeVisible();
  expect(requests).toHaveLength(0);
  expect(
    await page.evaluate(
      () =>
        (window as Window & { cameraRequests?: number }).cameraRequests ?? 0,
    ),
  ).toBe(0);

  await page
    .getByRole("button", { name: "Verify eligibility and begin monitoring" })
    .click();
  await expect(page.getByText("Monitoring session active")).toBeVisible();
  expect(requests[0]).toEqual({
    path: "/api/proctoring/monitoring/sessions",
    body: { attemptId },
  });

  await page.evaluate(() => window.dispatchEvent(new Event("blur")));
  await expect
    .poll(() => requests.filter(({ path }) => path.endsWith("/events")).length)
    .toBeGreaterThan(0);
  const evidenceBody = requests.find(({ path }) => path.endsWith("/events"))!
    .body as Record<string, unknown>;
  expect(evidenceBody.type).toBe("WINDOW_BLUR");
  expect(evidenceBody.schemaVersion).toBe(1);
  expect(evidenceBody).not.toHaveProperty("answer");
  expect(evidenceBody).not.toHaveProperty("url");

  await page
    .getByRole("button", { name: "Request camera and connect" })
    .click();
  await expect(page.getByText("Camera permission denied")).toBeVisible();
  expect(
    await page.evaluate(
      () =>
        (window as Window & { cameraRequests?: number }).cameraRequests ?? 0,
    ),
  ).toBe(1);
  expect(requests.some(({ path }) => path.includes("media-grants"))).toBe(
    false,
  );
  expect(
    await page.evaluate(() => ({
      local: localStorage.length,
      session: sessionStorage.length,
    })),
  ).toEqual({ local: 0, session: 0 });
});

test("Teacher uses fresh attempt lookup and sees distinct provenance without global discovery", async ({
  page,
}) => {
  await mockAuth(page, ["TEACHER"]);
  const paths: string[] = [];
  await page.route("**/api/proctoring/monitoring/sessions**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    paths.push(path);
    if (path.endsWith("/events")) {
      return json(route, {
        items: [
          {
            ordinal: 1,
            id: "b7a4c564-3c27-4e6d-91aa-a004334aa8f9",
            streamId: null,
            sequence: null,
            type: "ANSWER_PERSISTED",
            observedAt: "2026-10-16T10:00:00Z",
            questionNumber: 2,
            receivedAt: "2026-10-16T10:00:01Z",
            source: "ASSESSMENT_AUTHORITY",
            integritySha256: "a".repeat(64),
            schemaVersion: 1,
            actorId: session.studentId,
            assessmentRevision: "42",
          },
        ],
        nextAfter: null,
      });
    }
    return json(route, session);
  });
  await page.goto(`/app/monitoring/teacher/attempts/${attemptId}`);
  await expect(
    page.getByText(
      "Saved assessment fact · Question 2 · Assessment revision 42",
    ),
  ).toBeVisible();
  expect(paths[0]).toBe(
    `/api/proctoring/monitoring/sessions/by-attempt/${attemptId}`,
  );
  expect(paths).not.toContain("/api/proctoring/monitoring/sessions");
  await expect(page.getByText(/neither proves misconduct/i)).toBeVisible();
});

test("foreign or inaccessible Teacher Attempt remains masked", async ({
  page,
}) => {
  await mockAuth(page, ["TEACHER"]);
  await page.route(
    "**/api/proctoring/monitoring/sessions/by-attempt/**",
    (route) => json(route, { code: "PROCTORING_NOT_ACCESSIBLE" }, 404),
  );
  await page.goto(`/app/monitoring/teacher/attempts/${attemptId}`);
  await expect(page.getByText("Session not accessible")).toBeVisible();
  await expect(
    page.getByText(/No monitoring session is available for that attempt/),
  ).toBeVisible();
});
