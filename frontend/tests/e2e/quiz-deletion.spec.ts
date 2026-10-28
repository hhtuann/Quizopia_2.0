import { expect, test, type Page } from "@playwright/test";

// FB-015 minimal happy-path delete journey (mock-based, same route-mock
// strategy as quiz-authoring.spec.ts). NOTE: this spec exercises the UI
// against browser-level route mocks only. The real quiz-service deletion
// routes exist ONLY while QUIZ_DELETION_ENABLED=true (default OFF), so a
// real-runtime e2e cannot exercise deletion until that runtime flag is on.

const quizId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const operationId = "1c9d0d7e-5c8a-4b8f-9a55-1d3a6d3a2a11";

const teacher = {
  email: "teacher@gmail.com",
  id: "a7a4c564-3c27-4e6d-91aa-a004334aa8f9",
  roles: ["STUDENT", "TEACHER"],
  username: "teacher",
};

test.beforeEach(async ({ page }) => {
  await page
    .context()
    .addCookies([
      { name: "quizopia-locale", value: "en", url: "http://localhost:3000" },
    ]);
});

async function mockTeacherBootstrap(page: Page) {
  await page.route("**/api/auth/refresh", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        accessToken: "quiz-deletion-e2e-access",
        expiresIn: 300,
        tokenType: "Bearer",
      }),
    });
  });
  await page.route("**/api/auth/me", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify(teacher),
    });
  });
}

test("teacher deletes a quiz through exact-title confirmation and follows honest progress", async ({
  page,
}) => {
  await mockTeacherBootstrap(page);

  let deletionStatus = "ACCESS_REVOKED";
  let purged = false;
  await page.route("**/api/quizzes", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        items: purged
          ? []
          : [
              {
                quizId,
                title: "Deletable quiz",
                description: null,
                createdAt: "2026-10-20T08:00:00Z",
                updatedAt: "2026-10-20T08:00:00Z",
                latestVersionNumber: 2,
                deletionStatus: null,
              },
            ],
        nextCursor: null,
      }),
    });
  });
  await page.route(
    `**/api/quizzes/${quizId}/deletion-requests`,
    async (route) => {
      expect(route.request().method()).toBe("POST");
      const key = route.request().headers()["idempotency-key"];
      expect(key).toMatch(/^[A-Za-z0-9_-]{16,128}$/);
      const body = route.request().postDataJSON() as { confirmTitle: string };
      expect(body.confirmTitle).toBe("Deletable quiz");
      await route.fulfill({
        contentType: "application/json",
        status: 202,
        body: JSON.stringify({
          operationId,
          status: "QUIZ_FENCED",
        }),
      });
    },
  );
  await page.route(
    `**/api/quizzes/${quizId}/deletion-requests/${operationId}`,
    async (route) => {
      await route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify({
          operationId,
          status: deletionStatus,
          createdAt: "2026-10-20T08:00:00Z",
          updatedAt: "2026-10-20T08:01:00Z",
          lastReason: null,
          stages: ["REQUESTED"],
          ...(deletionStatus === "PRIMARY_PURGED"
            ? { note: "COPY_EXPIRY_PENDING" }
            : {}),
        }),
      });
      deletionStatus = "PRIMARY_PURGED";
      purged = true;
    },
  );

  await page.goto("/app/quizzes");
  const gate = page.getByRole("button", { name: "Switch to Teaching" });
  if (
    await gate
      .waitFor({ state: "visible", timeout: 3_000 })
      .then(() => true)
      .catch(() => false)
  ) {
    await gate.click();
  }

  await expect(
    page.getByRole("link", { name: "Deletable quiz" }),
  ).toBeVisible();

  // Near-miss typing keeps the destructive action locked.
  await page.getByRole("button", { name: "Delete quiz" }).click();
  const dialog = page.getByRole("dialog", {
    name: "Delete this quiz permanently?",
  });
  await expect(dialog).toBeVisible();
  await expect(
    dialog.getByText(
      "Students immediately lose access once revocation completes.",
    ),
  ).toBeVisible();
  const confirmField = dialog.getByLabel("Type the exact title to confirm");
  await confirmField.fill("deletable quiz");
  await expect(
    dialog.getByRole("button", { name: "Delete permanently" }),
  ).toBeDisabled();

  // Exact title unlocks the destructive action.
  await confirmField.fill("Deletable quiz");
  await dialog.getByRole("button", { name: "Delete permanently" }).click();

  // Honest progress: revoking, then deleting data. At the terminal state the
  // card panel is atomically replaced by the success notice — the notice
  // never claims full erasure because ALL_COPIES_ERASED never appears.
  await expect(page.getByText("Revoking access")).toBeVisible();
  await expect(page.getByText("Access blocked · Deleting data")).toBeVisible({
    timeout: 6_000,
  });
  await expect(page.getByText("Quiz deleted")).toBeVisible({ timeout: 6_000 });
  await expect(
    page.getByText(
      "“Deletable quiz” was deleted. Students can no longer access it.",
    ),
  ).toBeVisible();
  await expect(page.getByText("Fully deleted")).toHaveCount(0);
});
