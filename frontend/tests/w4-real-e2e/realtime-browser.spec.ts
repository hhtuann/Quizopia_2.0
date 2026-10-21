import { expect, test, type Page } from "@playwright/test";

/**
 * W4 real-browser acceptance journeys. The disposable real runtime (Gateway, Identity,
 * Assessment, Classroom, RabbitMQ, Redis, PostgreSQL and this dev server) is owned by the
 * topology harness; these specs only drive a real Chromium against it. Accounts are
 * pre-seeded disposable test identities; no production resources are involved.
 */

function gateway(): string {
  const url = process.env.W4_REAL_GATEWAY_URL;
  if (!url || !/^https?:\/\/127\.0\.0\.1:\d+$/.test(url))
    throw new Error(
      "W4_REAL_GATEWAY_URL must point at the disposable loopback runtime",
    );
  return url;
}

const TEACHER_EMAIL = process.env.W4_REAL_TEACHER_EMAIL ?? "";
const STUDENT_EMAIL = process.env.W4_REAL_STUDENT_EMAIL ?? "";
const USER_PASSWORD = process.env.W4_REAL_USER_PASSWORD ?? "";
const PUBLICATION_ID = process.env.W4_REAL_PUBLICATION_ID ?? "";
const publicationBase = "/api/assessments/publications";

class RealtimeBrowserActor {
  accessToken = "";
  private readonly consoleMessages: string[] = [];
  private readonly networkUrls: string[] = [];
  private readonly authStatuses: string[] = [];

  constructor(readonly page: Page) {}

  async watch() {
    this.page.on("request", (request) => {
      this.networkUrls.push(request.url());
      const authorization = request.headers().authorization;
      if (
        authorization?.startsWith("Bearer ") &&
        authorization.split(".").length === 3
      )
        this.accessToken = authorization.slice(7);
    });
    this.page.on("response", (response) => {
      const pathname = new URL(response.url()).pathname;
      if (
        pathname.startsWith("/api/auth/") ||
        pathname.startsWith("/api/realtime")
      )
        this.authStatuses.push(pathname + ":" + response.status());
    });
    this.page.on("console", (message) =>
      this.consoleMessages.push(message.text()),
    );
    this.page.on("websocket", (socket) => {
      this.networkUrls.push(socket.url());
    });
  }

  async login(email: string, password: string) {
    await this.page.goto("/login");
    await this.page.getByLabel("Username or email").fill(email);
    await this.page.getByLabel("Password").fill(password);
    const me = this.page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === "/api/auth/me" &&
        response.status() === 200,
      { timeout: 60_000 },
    );
    await this.page.getByRole("button", { name: "Sign in" }).click();
    // Surface the real auth exchange when hydration fails instead of hanging silently.
    const hydrationFailure = this.page
      .getByText("Account verification is temporarily unavailable")
      .waitFor({ timeout: 15_000 })
      .then(() => true)
      .catch(() => false);
    const race = await Promise.race([
      me.then(() => "authenticated"),
      hydrationFailure.then((failed) => (failed ? "failed" : "authenticated")),
    ]);
    if (race === "failed") {
      throw new Error(
        "Login hydration failed; auth exchange: " +
          (this.authStatuses.join(", ") || "none captured"),
      );
    }
    await expect(this.page).toHaveURL(/\/app/);
  }

  /** Deep navigation must fully re-bootstrap; failures surface the real auth exchange. */
  async openAuthenticated(path: string) {
    await this.page.goto(path);
    const failure = this.page
      .getByText("Account access is temporarily unavailable")
      .waitFor({ timeout: 20_000 })
      .then(() => true)
      .catch(() => false);
    if (await failure) {
      throw new Error(
        "Deep-link bootstrap failed; auth exchange: " +
          (this.authStatuses.join(", ") || "none captured"),
      );
    }
  }

  async fetch<T>(
    path: string,
    method: "GET" | "POST" | "PATCH" = "GET",
    body?: unknown,
    key?: string,
  ): Promise<{ status: number; body: T }> {
    return this.page.evaluate(
      async ({ base, token, path, method, body, key }) => {
        const response = await fetch(base + path, {
          method,
          headers: {
            Authorization: `Bearer ${token}`,
            Accept: "application/json",
            ...(body === undefined
              ? {}
              : { "Content-Type": "application/json" }),
            ...(key ? { "Idempotency-Key": key } : {}),
          },
          ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        });
        const text = await response.text();
        return {
          status: response.status,
          body: text ? JSON.parse(text) : null,
        };
      },
      { base: gateway(), token: this.accessToken, path, method, body, key },
    );
  }

  async assertRealtimeSecrecy() {
    const snapshot = await this.page.evaluate(async () => ({
      cookie: document.cookie,
      local: JSON.stringify(localStorage),
      session: JSON.stringify(sessionStorage),
    }));
    const surfaces = [
      this.page.url(),
      ...this.networkUrls,
      ...this.consoleMessages,
    ];
    const websocketUrls = this.networkUrls.filter((url) =>
      url.includes("/ws/realtime"),
    );
    expect(
      websocketUrls.length,
      "browser realtime uses the WebSocket surface",
    ).toBeGreaterThan(0);
    for (const url of websocketUrls) {
      expect(url.includes("?")).toBe(false);
      expect(url.includes("ticket=")).toBe(false);
      expect(url.includes("token")).toBe(false);
    }
    expect(snapshot.cookie.includes(this.accessToken)).toBe(false);
    expect(snapshot.local.includes(this.accessToken)).toBe(false);
    expect(snapshot.session.includes(this.accessToken)).toBe(false);
    expect(
      surfaces.some((surface) => surface.includes(this.accessToken)),
      "access token absent from URLs and console output",
    ).toBe(false);
  }
}

test.describe("W4 real-browser realtime acceptance", () => {
  test("teacher publication view connects, hints drive REST resync, UI shows authoritative state", async ({
    page,
  }) => {
    test.skip(
      !TEACHER_EMAIL || !PUBLICATION_ID,
      "harness identity/publication env missing",
    );
    const teacher = new RealtimeBrowserActor(page);
    await teacher.watch();
    await teacher.login(TEACHER_EMAIL, USER_PASSWORD);

    await teacher.openAuthenticated(`/app/assessments/${PUBLICATION_ID}`);
    if (
      await page
        .getByRole("button", { name: "Switch to Teaching workspace" })
        .isVisible()
    )
      await page
        .getByRole("button", { name: "Switch to Teaching workspace" })
        .click();
    await expect(
      page.getByRole("heading", { name: "W4 acceptance publication" }),
    ).toBeVisible();

    // E1: the real browser completes ticket → WS AUTH → AUTHENTICATED (status inline).
    const status = page.locator("[data-realtime-state]");
    await expect(status.first()).toHaveAttribute(
      "data-realtime-state",
      "authenticated",
      {
        timeout: 30_000,
      },
    );

    // E2: a real committed extension flows outbox → RabbitMQ → this browser's hint,
    // and the UI reconciles to authoritative REST state (new revision), not payload text.
    const owner = await teacher.fetch<{ revision: number }>(
      `${publicationBase}/${PUBLICATION_ID}`,
    );
    expect(owner.status).toBe(200);
    const endAt = new Date(Date.now() + 4 * 60 * 60 * 1000)
      .toISOString()
      .replace(/\.\d{3}Z$/, "Z");
    const extend = await teacher.fetch<{ revision: number }>(
      `${publicationBase}/${PUBLICATION_ID}/schedule`,
      "PATCH",
      {
        expectedRevision: owner.body.revision,
        endAt,
        durationMinutes: 30,
      },
    );
    expect(extend.status).toBe(200);
    await expect(
      page.getByText(`Revision ${owner.body.revision + 1}`),
    ).toBeVisible({
      timeout: 30_000,
    });
    await teacher.assertRealtimeSecrecy();
  });

  test("student attempt view protects unsaved drafts across realtime and recovery resyncs", async ({
    page,
    browser,
  }) => {
    test.skip(
      !STUDENT_EMAIL || !PUBLICATION_ID,
      "harness identity/publication env missing",
    );
    const student = new RealtimeBrowserActor(page);
    await student.watch();
    await student.login(STUDENT_EMAIL, USER_PASSWORD);

    const started = await student.fetch<{ attemptId: string }>(
      `${publicationBase}/${PUBLICATION_ID}/attempts/start`,
      "POST",
      {},
      crypto.randomUUID(),
    );
    expect(
      started.status,
      "real attempt start through the browser session",
    ).toBe(200);

    await student.openAuthenticated(`/app/attempts/${started.body.attemptId}`);
    const status = page.locator("[data-realtime-state]");
    await expect(status.first()).toHaveAttribute(
      "data-realtime-state",
      "authenticated",
      {
        timeout: 30_000,
      },
    );

    const field = page.getByRole("textbox").first();
    await field.fill("0007");
    await expect(field).toHaveValue("0007");

    // E3: visibility recovery triggers an authoritative REST resync; the unsaved draft
    // must survive it (stale server answers never overwrite local unsaved work).
    await page.evaluate(() => {
      Object.defineProperty(document, "visibilityState", {
        configurable: true,
        get: () => "hidden",
      });
      document.dispatchEvent(new Event("visibilitychange"));
      Object.defineProperty(document, "visibilityState", {
        configurable: true,
        get: () => "visible",
      });
      document.dispatchEvent(new Event("visibilitychange"));
    });
    const refreshed = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname ===
          `/api/assessments/attempts/${started.body.attemptId}` &&
        response.request().method() === "GET" &&
        response.status() === 200,
      { timeout: 30_000 },
    );
    await refreshed;
    await expect(field).toHaveValue("0007", { timeout: 10_000 });

    // E2/E3: a second browser session of the same student submits the attempt; the
    // first view receives the real ATTEMPT_INVALIDATED and reconciles authoritatively.
    const secondContext = await browser.newContext();
    const secondPage = await secondContext.newPage();
    const second = new RealtimeBrowserActor(secondPage);
    await second.watch();
    await second.login(STUDENT_EMAIL, USER_PASSWORD);
    const view = await second.fetch<{ revision: string }>(
      `/api/assessments/attempts/${started.body.attemptId}`,
    );
    expect(view.status).toBe(200);
    const submit = await second.fetch(
      `/api/assessments/attempts/${started.body.attemptId}/submit`,
      "POST",
      {
        expectedRevision: view.body.revision,
        answers: [
          {
            questionNumber: 1,
            answer: { type: "NUMERIC_FILL", value: "0001" },
          },
        ],
      },
      crypto.randomUUID(),
    );
    expect(submit.status).toBe(200);
    await expect(
      page.getByText(/Attempt submitted|submitted/i).first(),
    ).toBeVisible({ timeout: 30_000 });
    await secondContext.close();
    await student.assertRealtimeSecrecy();
  });
});
