import { expect, test, type Page } from "@playwright/test";

const user = {
  email: "learner01@gmail.com",
  id: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
  roles: ["STUDENT", "TEACHER"],
  username: "learner01",
};

async function mockAnonymousBootstrap(page: Page) {
  await page.route("**/api/auth/refresh", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      status: 401,
      body: JSON.stringify({
        code: "AUTH_REFRESH_FAILED",
        message: "Refresh failed.",
        path: "/api/auth/refresh",
        status: 401,
        traceId: null,
      }),
    });
  });
}

async function mockAuthenticatedBootstrap(page: Page) {
  await page.route("**/api/auth/refresh", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        accessToken: "e2e-bootstrap-access",
        expiresIn: 300,
        tokenType: "Bearer",
      }),
    });
  });
  await page.route("**/api/auth/me", async (route) => {
    expect(route.request().headers().authorization).toBe(
      "Bearer e2e-bootstrap-access",
    );
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify(user),
    });
  });
}

test("application reload restores an intercepted refresh session before showing authenticated content", async ({
  page,
}) => {
  await mockAuthenticatedBootstrap(page);

  await page.goto("/app");

  await expect(
    page.getByRole("heading", { name: "Your Quizopia workspace" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: /Open user menu for learner01/ }),
  ).toContainText("Learning");
  await expect(
    page.getByRole("heading", { name: "Checking your session" }),
  ).toHaveCount(0);

  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Your Quizopia workspace" }),
  ).toBeVisible();
});

test("application bootstrap with no refresh session settles as anonymous", async ({
  page,
}) => {
  await mockAnonymousBootstrap(page);
  await page.goto("/app");

  await expect(
    page.getByRole("heading", { name: "Sign in to continue" }),
  ).toBeVisible();
  await expect(page.getByRole("status")).toContainText(
    "Account access required",
  );
  await expect(
    page.getByRole("link", { name: "Go to sign in" }),
  ).toHaveAttribute("href", "/login");
});

test("authenticated application entry remains within a 375px viewport", async ({
  page,
}) => {
  await mockAuthenticatedBootstrap(page);
  await page.setViewportSize({ height: 812, width: 375 });
  await page.goto("/app");

  await expect(
    page.getByRole("heading", { name: "Your Quizopia workspace" }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("authenticated application supports skip navigation and visible keyboard focus", async ({
  page,
}) => {
  await mockAuthenticatedBootstrap(page);
  await page.goto("/app");
  await expect(
    page.getByRole("heading", { name: "Your Quizopia workspace" }),
  ).toBeVisible();

  await page.keyboard.press("Tab");
  const skipLink = page.getByRole("link", { name: "Skip to main content" });
  await expect(skipLink).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.locator("#main-content")).toBeFocused();
});

test("intercepted logout clears the frontend session and returns to auth-required UX", async ({
  page,
}) => {
  await mockAuthenticatedBootstrap(page);
  let logoutCalls = 0;
  await page.route("**/api/auth/logout", async (route) => {
    logoutCalls += 1;
    await route.fulfill({ status: 204 });
  });
  await page.goto("/app");
  await expect(
    page.getByRole("heading", { name: "Your Quizopia workspace" }),
  ).toBeVisible();

  await page
    .getByRole("button", { name: /Open user menu for learner01/ })
    .click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();

  await expect(
    page.getByRole("heading", { name: "Sign in to continue" }),
  ).toBeVisible();
  expect(logoutCalls).toBe(1);
});

test("student enables teacher only after refresh and authoritative current-user hydration", async ({
  page,
}) => {
  let refreshCalls = 0;
  let enablementCalls = 0;
  const observedRequests: Array<{
    authorization?: string;
    method: string;
    postData: string | null;
    url: string;
  }> = [];

  await page.route("**/api/auth/refresh", async (route) => {
    refreshCalls += 1;
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        accessToken: refreshCalls === 1 ? "student-access" : "teacher-access",
        expiresIn: 300,
        tokenType: "Bearer",
      }),
    });
  });
  await page.route("**/api/auth/me", async (route) => {
    const authorization = route.request().headers().authorization;
    observedRequests.push({
      authorization,
      method: route.request().method(),
      postData: route.request().postData(),
      url: route.request().url(),
    });
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        ...user,
        roles:
          authorization === "Bearer teacher-access"
            ? ["STUDENT", "TEACHER"]
            : ["STUDENT"],
      }),
    });
  });
  await page.route("**/api/auth/teacher-enablement", async (route) => {
    enablementCalls += 1;
    observedRequests.push({
      authorization: route.request().headers().authorization,
      method: route.request().method(),
      postData: route.request().postData(),
      url: route.request().url(),
    });
    await route.fulfill({ status: 204 });
  });

  await page.goto("/app");
  await page
    .getByRole("button", { name: /Open user menu for learner01/ })
    .click();
  await expect(
    page.getByRole("menuitem", { name: "Register as teacher" }),
  ).toBeVisible();
  await expect(
    page.getByRole("menuitem", { name: "Switch to Teaching" }),
  ).toHaveCount(0);

  await page.getByRole("menuitem", { name: "Register as teacher" }).click();

  await expect(page.getByRole("status")).toContainText(
    "Teacher access is ready",
  );
  await expect(
    page.getByRole("menuitem", { name: "Switch to Teaching" }),
  ).toBeVisible();
  expect(enablementCalls).toBe(1);
  expect(refreshCalls).toBe(2);
  const enablementRequest = observedRequests.find((request) =>
    request.url.endsWith("/api/auth/teacher-enablement"),
  );
  expect(enablementRequest).toMatchObject({
    authorization: "Bearer student-access",
    method: "POST",
    postData: null,
  });
  expect(
    observedRequests.some(
      (request) =>
        request.url.endsWith("/api/auth/me") &&
        request.authorization === "Bearer teacher-access",
    ),
  ).toBe(true);

  await page.getByRole("menuitem", { name: "Switch to Teaching" }).click();
  await expect(
    page.getByRole("link", { name: "Quiz authoring", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", {
      name: /Open user menu for learner01, Teaching workspace/,
    }),
  ).toBeVisible();
  expect(
    await page.evaluate(async () => ({
      indexedDb:
        "databases" in indexedDB ? (await indexedDB.databases()).length : 0,
      localStorage: localStorage.length,
      sessionStorage: sessionStorage.length,
    })),
  ).toEqual({ indexedDb: 0, localStorage: 0, sessionStorage: 0 });
});

// Workspace-switch journeys chain several SPA navigations. They run serially
// with a generous budget: parallel workers contend for the shared dev server's
// on-demand route compilation on developer machines, so a single retry guards
// against that environment-level flakiness without weakening assertions.
test.describe("workspace switching", () => {
  test.describe.configure({ mode: "serial", retries: 1 });
  test.setTimeout(90_000);

  test("workspace switch from a teacher route lands on /app with the new workspace home", async ({
    page,
  }) => {
    await mockAuthenticatedBootstrap(page);
    await page.goto("/app");
    // Wait for a fully authenticated render before interacting: under heavy
    // parallel dev-server load the bootstrap can still be in flight.
    await expect(
      page.getByRole("heading", { name: "Your Quizopia workspace" }),
    ).toBeVisible({ timeout: 15_000 });

    // Enter Teaching and navigate to a Teaching-only route.
    await page
      .getByRole("button", { name: /Open user menu for learner01/ })
      .click();
    await page.getByRole("menuitem", { name: "Switch to Teaching" }).click();
    await expect(
      page.getByRole("link", { name: "Quiz authoring", exact: true }),
    ).toBeVisible();
    await page
      .getByRole("link", { name: "Quiz authoring", exact: true })
      .click();
    await expect(page).toHaveURL(/\/app\/quizzes$/);
    await expect(
      page.getByRole("heading", { name: "Quiz authoring" }),
    ).toBeVisible({ timeout: 15_000 });

    // Switching back to Learning must leave the Teaching URL behind.
    await page
      .getByRole("button", { name: /Open user menu for learner01/ })
      .click();
    await page.getByRole("menuitem", { name: "Switch to Learning" }).click();

    await expect(page).toHaveURL(/\/app$/);
    await expect(
      page.getByRole("heading", { name: "Your Quizopia workspace" }),
    ).toBeVisible();
    await expect(
      page.getByRole("link", { name: "Enter an assessment", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", {
        name: /Open user menu for learner01, Learning workspace/,
      }),
    ).toBeVisible();
  });

  test("workspace switch from a student route lands on /app in the Teaching workspace", async ({
    page,
  }) => {
    await mockAuthenticatedBootstrap(page);
    await page.goto("/app/assessments/join");
    await expect(
      page.getByRole("heading", { name: "Enter an assessment" }),
    ).toBeVisible();

    await page
      .getByRole("button", { name: /Open user menu for learner01/ })
      .click();
    await page.getByRole("menuitem", { name: "Switch to Teaching" }).click();

    await expect(page).toHaveURL(/\/app$/);
    await expect(
      page.getByRole("link", { name: "Quiz authoring", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", {
        name: /Open user menu for learner01, Teaching workspace/,
      }),
    ).toBeVisible();
  });
  test("unsaved assessment form can cancel a workspace switch without losing state or URL", async ({
    page,
  }) => {
    const quizId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
    const quizVersionId = "9bd4c564-3c27-4e6d-91aa-a004334aa8f7";
    await mockAuthenticatedBootstrap(page);
    await page.route(`**/api/quizzes`, (route) =>
      route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify({
          items: [
            {
              quizId,
              title: "Guarded quiz",
              description: "Draft",
              createdAt: "2026-10-16T07:00:00Z",
              updatedAt: "2026-10-16T07:00:00Z",
              latestVersionNumber: null,
            },
          ],
          nextCursor: null,
        }),
      }),
    );
    await page.route(`**/api/quizzes/${quizId}/draft`, (route) =>
      route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify({
          quizId,
          title: "Guarded quiz",
          description: "Draft",
          authoringSource: "Câu 1 [NUMERIC_FILL]: Source?\nĐáp án: 42",
          createdAt: "2026-10-16T07:00:00Z",
          updatedAt: "2026-10-16T07:00:00Z",
        }),
      }),
    );
    await page.route(`**/api/quizzes/${quizId}/versions**`, async (route) => {
      const path = new URL(route.request().url()).pathname;
      const value = path.endsWith("/versions")
        ? {
            items: [
              {
                id: quizVersionId,
                quizId,
                versionNumber: 3,
                titleSnapshot: "Guarded quiz",
                descriptionSnapshot: "Version three",
                contentSchemaVersion: 1,
                createdAt: "2026-10-16T07:00:00Z",
              },
            ],
            nextCursor: null,
          }
        : {
            id: quizVersionId,
            quizId,
            versionNumber: 3,
            titleSnapshot: "Guarded quiz",
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
          };
      await route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify(value),
      });
    });

    await page.goto("/app");
    // Enter Teaching and reach the assessment create form via client-side
    // navigation only: workspace state is per-session and a full reload would
    // re-derive LEARNING.
    await page
      .getByRole("button", { name: /Open user menu for learner01/ })
      .click();
    await page.getByRole("menuitem", { name: "Switch to Teaching" }).click();
    await expect(
      page.getByRole("link", { name: "Quiz authoring", exact: true }),
    ).toBeVisible();
    await page.getByRole("link", { name: "Open quiz authoring" }).click();
    await expect(page).toHaveURL(/\/app\/quizzes$/);
    await page.getByRole("link", { name: "Guarded quiz" }).click();
    const editorTitle = page.getByRole("textbox", { name: "Quiz title" });
    await expect(editorTitle).toBeVisible();

    // The editor draft stays clean: navigate to the version history dialog and
    // create an assessment from version 3 (client-side navigation).
    await page.getByRole("button", { name: "Published versions" }).click();
    await page
      .getByRole("list", { name: "Published version history" })
      .getByRole("button", { name: /Version 3/ })
      .click();
    await page
      .getByRole("button", { name: "Create assessment from version 3" })
      .click();
    await expect(page).toHaveURL(
      new RegExp(
        `/app/assessments/new\\?quizId=${quizId}&quizVersionId=${quizVersionId}&versionNumber=3$`,
      ),
    );
    await page.getByRole("radio", { name: "Public", exact: true }).check();

    // Make the create form dirty.
    const title = page.getByRole("textbox", { name: "Publication title" });
    await title.fill("Unsaved draft configuration");

    // Cancel the switch: workspace, URL and form content stay intact.
    await page
      .getByRole("button", { name: /Open user menu for learner01/ })
      .click();
    page.once("dialog", (dialog) => dialog.dismiss());
    await page.getByRole("menuitem", { name: "Switch to Learning" }).click();
    await expect(page).toHaveURL(/\/app\/assessments\/new/);
    await expect(
      page.getByRole("button", {
        name: /Open user menu for learner01, Teaching workspace/,
      }),
    ).toBeVisible();
    await expect(title).toHaveValue("Unsaved draft configuration");

    // The guard cancel kept the menu open (the user may reconsider); accepting
    // the guard on the directly re-clicked item completes the switch to /app.
    page.once("dialog", (dialog) => dialog.accept());
    await page.getByRole("menuitem", { name: "Switch to Learning" }).click();
    await expect(page).toHaveURL(/\/app$/);
    await expect(
      page.getByRole("link", { name: "Enter an assessment", exact: true }),
    ).toBeVisible();
  });
});
