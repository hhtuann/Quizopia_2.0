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

async function enterTeaching(page: Page, path = "/app/quizzes") {
  await page.goto(path);
  const gate = page.getByRole("button", {
    name: /Switch to Teaching|Chuyển sang Giảng dạy/,
  });
  if (
    await gate
      .waitFor({ state: "visible", timeout: 1500 })
      .then(() => true)
      .catch(() => false)
  ) {
    await gate.click();
  }
}

function receipt(status: string) {
  return {
    operationId,
    status,
    createdAt: "2026-10-20T08:00:00Z",
    updatedAt: "2026-10-20T08:01:00Z",
    lastReason: null,
    stages: ["REQUESTED"],
    note: status === "PRIMARY_PURGED" ? "COPY_EXPIRY_PENDING" : null,
  };
}

function libraryRow(
  title: string | null,
  deletionStatus: string | null = null,
) {
  return {
    quizId,
    title,
    deletionStatus,
    description: null,
    createdAt: "2026-10-20T08:00:00Z",
    updatedAt: "2026-10-20T08:00:00Z",
    latestVersionNumber: null,
  };
}

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
  // Keep the immediate first poll from replacing the 202 state before it can
  // be asserted. The fixture, not application timing, controls each transition.
  let releaseStatusRead!: () => void;
  const statusReadAllowed = new Promise<void>((resolve) => {
    releaseStatusRead = resolve;
  });
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
      await statusReadAllowed;
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
          note:
            deletionStatus === "PRIMARY_PURGED" ? "COPY_EXPIRY_PENDING" : null,
        }),
      });
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
  try {
    await expect(page.getByText("Revoking access")).toBeVisible();
  } finally {
    releaseStatusRead();
  }
  await expect(page.getByText("Access blocked · Deleting data")).toBeVisible({
    timeout: 6_000,
  });
  deletionStatus = "PRIMARY_PURGED";
  purged = true;
  await expect(page.getByText("Quiz deleted")).toBeVisible({ timeout: 6_000 });
  await expect(
    page.getByText(
      "“Deletable quiz” was deleted. Students can no longer access it.",
    ),
  ).toBeVisible();
  await expect(page.getByText("Fully deleted")).toHaveCount(0);
});

for (const title of [null, "", "   "]) {
  test(`historical untitled ${JSON.stringify(title)} requires keyboard confirmation and sends an explicit empty title`, async ({
    page,
  }) => {
    await mockTeacherBootstrap(page);
    let posts = 0;
    await page.route("**/api/quizzes", (route) =>
      route.fulfill({ json: { items: [libraryRow(title)], nextCursor: null } }),
    );
    await page.route(
      `**/api/quizzes/${quizId}/deletion-requests`,
      async (route) => {
        posts++;
        expect(route.request().postDataJSON()).toEqual({ confirmTitle: "" });
        await route.fulfill({
          status: 202,
          json: { operationId, status: "QUIZ_FENCED" },
        });
      },
    );
    await page.route(
      `**/api/quizzes/${quizId}/deletion-requests/${operationId}`,
      (route) => route.fulfill({ json: receipt("PENDING_POLICY") }),
    );
    await enterTeaching(page);
    await page.getByRole("button", { name: "Delete quiz" }).click();
    const dialog = page.getByRole("dialog");
    const confirm = dialog.getByRole("button", { name: "Delete permanently" });
    await expect(confirm).toBeDisabled();
    const checkbox = dialog.getByRole("checkbox", {
      name: "I understand that permanently deleting this untitled quiz is irreversible.",
    });
    await checkbox.focus();
    await page.keyboard.press("Space");
    await expect(confirm).toBeEnabled();
    await confirm.click();
    await expect(
      page.getByText("Waiting for the mandatory retention period"),
    ).toBeVisible();
    expect(posts).toBe(1);
    await expect(page.getByText("Fully deleted")).toHaveCount(0);
  });
}

for (const surface of [
  "library",
  "fenced-detail",
  "purged-detail-404",
  "purged-detail-410",
]) {
  test(`${surface} recovers durable owner status before and after reload without an in-session operation`, async ({
    page,
  }) => {
    await mockTeacherBootstrap(page);
    let discoveries = 0;
    let mutations = 0;
    await page.route("**/api/quizzes", (route) =>
      route.fulfill({
        json: { items: [libraryRow(null, "QUIZ_FENCED")], nextCursor: null },
      }),
    );
    await page.route(`**/api/quizzes/${quizId}/draft`, (route) => {
      if (surface.startsWith("purged")) {
        const status = surface.endsWith("410") ? 410 : 404;
        return route.fulfill({
          status,
          json: {
            code: "QUIZ_NOT_FOUND",
            message: "Not found",
            status,
            path: `/api/quizzes/${quizId}/draft`,
          },
        });
      }
      return route.fulfill({
        json: {
          quizId,
          title: null,
          description: null,
          deletionStatus: "QUIZ_FENCED",
          authoringSource: null,
          createdAt: "2026-10-20T08:00:00Z",
          updatedAt: "2026-10-20T08:00:00Z",
        },
      });
    });
    await page.route(
      `**/api/quizzes/${quizId}/deletion-requests`,
      async (route) => {
        mutations++;
        await route.abort();
      },
    );
    await page.route(
      `**/api/quizzes/${quizId}/deletion-requests/latest`,
      (route) => {
        discoveries++;
        return route.fulfill({
          headers: { "Cache-Control": "no-store" },
          json: receipt(
            surface.startsWith("purged")
              ? "PRIMARY_PURGED"
              : "BLOCKED_BY_RETENTION",
          ),
        });
      },
    );
    await enterTeaching(
      page,
      surface === "library" ? "/app/quizzes" : `/app/quizzes/${quizId}`,
    );
    const text = surface.startsWith("purged")
      ? "Primary data deleted · Waiting for backup copies to be erased"
      : "Waiting for the mandatory retention period";
    await expect(page.getByText(text)).toBeVisible();
    await page.reload();
    await expect(page.getByText(text)).toBeVisible();
    expect(discoveries).toBeGreaterThanOrEqual(2);
    expect(mutations).toBe(0);
    await expect(page.getByText("Fully deleted")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Delete quiz" })).toHaveCount(
      0,
    );
  });
}

test("Vietnamese untitled confirmation remains explicit and accessible", async ({
  page,
}) => {
  await page
    .context()
    .addCookies([
      { name: "quizopia-locale", value: "vi", url: "http://localhost:3000" },
    ]);
  await mockTeacherBootstrap(page);
  await page.route("**/api/quizzes", (route) =>
    route.fulfill({ json: { items: [libraryRow(null)], nextCursor: null } }),
  );
  await enterTeaching(page);
  await page.getByRole("button", { name: "Xóa bộ câu hỏi" }).click();
  const checkbox = page.getByRole("checkbox", {
    name: "Tôi hiểu rằng việc xóa vĩnh viễn bộ câu hỏi chưa có tiêu đề này là không thể hoàn tác.",
  });
  await expect(checkbox).not.toBeChecked();
  await expect(
    page.getByRole("button", { name: "Xóa vĩnh viễn", exact: true }),
  ).toBeDisabled();
  await checkbox.check();
  await expect(
    page.getByRole("button", { name: "Xóa vĩnh viễn", exact: true }),
  ).toBeEnabled();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("an uncertain delete survives reload and reconciles before replaying the same key", async ({
  page,
}) => {
  await mockTeacherBootstrap(page);
  const keys: string[] = [];
  let reconciled = false;
  await page.route("**/api/quizzes", (route) =>
    route.fulfill({
      json: { items: [libraryRow("Retry quiz")], nextCursor: null },
    }),
  );
  await page.route(
    `**/api/quizzes/${quizId}/deletion-requests/latest`,
    (route) => {
      reconciled = true;
      return route.fulfill({
        status: 404,
        json: {
          code: "QUIZ_DELETION_NOT_FOUND",
          message: "Not found",
          status: 404,
          path: "/",
        },
      });
    },
  );
  await page.route(
    `**/api/quizzes/${quizId}/deletion-requests`,
    async (route) => {
      keys.push(route.request().headers()["idempotency-key"] ?? "");
      expect(route.request().postDataJSON()).toEqual({
        confirmTitle: "Retry quiz",
      });
      if (keys.length === 1) {
        await route.abort("failed");
      } else {
        expect(reconciled).toBe(true);
        await route.fulfill({
          status: 202,
          json: { operationId, status: "QUIZ_FENCED" },
        });
      }
    },
  );
  await page.route(
    `**/api/quizzes/${quizId}/deletion-requests/${operationId}`,
    (route) => route.fulfill({ json: receipt("WAITING_DEPENDENCY") }),
  );
  await enterTeaching(page);
  async function confirm() {
    await page.getByRole("button", { name: "Delete quiz" }).click();
    await page.getByLabel("Type the exact title to confirm").fill("Retry quiz");
    await page.getByRole("button", { name: "Delete permanently" }).click();
  }
  await confirm();
  await expect(
    page.getByText("Quizopia could not connect. Try again."),
  ).toBeVisible();
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await page.reload();
  await confirm();
  await expect(
    page.getByText(
      "Quizopia is retrying automatically; nothing needs your attention right now.",
    ),
  ).toBeVisible();
  expect(keys).toHaveLength(2);
  expect(keys[1]).toBe(keys[0]);
});
