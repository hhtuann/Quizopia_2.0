import { expect, test, type Page } from "@playwright/test";

const gateway = process.env.W4F_DEMO_GATEWAY ?? "";
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(gateway))
  throw new Error("Owned private Gateway required");
const password = process.env.W4F_DEMO_PASSWORD ?? "";
const classroom = process.env.W4F_DEMO_CLASSROOM ?? "";
class Actor {
  private token = "";
  constructor(readonly page: Page) {
    page.on("request", (request) => {
      const auth = request.headers().authorization;
      if (auth?.startsWith("Bearer ")) this.token = auth.slice(7);
    });
  }
  async login(email: string) {
    await this.page.context().addCookies([
      {
        name: "quizopia-locale",
        value: "en",
        url: process.env.W4F_DEMO_FRONTEND!,
      },
    ]);
    await this.page.goto("/login");
    await this.page.getByLabel("Username or email").fill(email);
    await this.page.getByLabel("Password").fill(password);
    await this.page.getByRole("button", { name: "Sign in" }).click();
    await expect(this.page).toHaveURL(/\/app/);
  }
  async request(path: string, method = "GET", body?: unknown, key?: string) {
    return this.page.evaluate(
      async ({ url, token, method, body, key }) => {
        const response = await fetch(url, {
          method,
          cache: "no-store",
          headers: {
            authorization: `Bearer ${token}`,
            ...(body === undefined
              ? {}
              : { "content-type": "application/json" }),
            ...(key ? { "Idempotency-Key": key } : {}),
          },
          body: body === undefined ? undefined : JSON.stringify(body),
        });
        const text = await response.text();
        return {
          status: response.status,
          cache: response.headers.get("cache-control"),
          body: text.length === 0 ? null : JSON.parse(text),
        };
      },
      { url: gateway + path, token: this.token, method, body, key },
    );
  }
}

test("fictional Teacher publishes and reviews OPEN work; opted-in and declined Students retain academic rights", async ({
  browser,
}) => {
  const teacherPage = await browser.newPage(),
    studentPage = await browser.newPage();
  const teacher = new Actor(teacherPage),
    student = new Actor(studentPage);
  await teacher.login(process.env.W4F_DEMO_TEACHER!);
  await student.login(process.env.W4F_DEMO_STUDENT!);
  const source =
    "Câu 1 [SINGLE_CHOICE]: Fictional demo choice\nA. Alpha\nB. Beta\n*C. Gamma\nD. Delta";
  const draft = await teacher.request("/api/quizzes", "POST", {
    title: "Fictional private audit demo",
    description: null,
    authoringSource: source,
  });
  expect(draft.status).toBe(201);
  const version = await teacher.request(
    `/api/quizzes/${draft.body.quizId}/versions`,
    "POST",
  );
  expect(version.status).toBe(201);
  const pubIds: string[] = [];
  for (const audience of ["PUBLIC", "CLASS"]) {
    const configuration = {
      title: `Fictional ${audience} demo`,
      description: null,
      quizId: draft.body.quizId,
      quizVersionId: version.body.id,
      versionNumber: version.body.versionNumber,
      audience,
      classroomId: audience === "CLASS" ? classroom : null,
      startAt: new Date(Date.now() - 60000).toISOString(),
      endAt: new Date(Date.now() + 3600000).toISOString(),
      durationMinutes: 0,
      monitoringChoice: "DISABLED",
      maxAttempts: 0,
    };
    const created = await teacher.request(
      "/api/assessments/publications",
      "POST",
      configuration,
      crypto.randomUUID(),
    );
    expect(created.status).toBe(201);
    const pub = created.body.publicationId;
    pubIds.push(pub);
    const opened = await teacher.request(
      `/api/assessments/publications/${pub}/open`,
      "POST",
      { expectedRevision: created.body.revision },
    );
    expect(opened.status).toBe(200);
    const started = await student.request(
      `/api/assessments/publications/${pub}/attempts/start`,
      "POST",
      audience === "PUBLIC" ? { locator: opened.body.privateLocator } : {},
      crypto.randomUUID(),
    );
    expect([200, 201].includes(started.status)).toBe(true);
    const attempt = started.body.attemptId,
      base = `/api/assessments/publications/${pub}/attempts/${attempt}`;
    await studentPage.goto(`/app/attempts/${attempt}`);
    // Surface has an accessible label on a section-like component; query by its heading as well.
    await expect(
      studentPage.getByRole("heading", {
        name: "Optional interaction history",
      }),
    ).toBeVisible();
    if (audience === "PUBLIC") {
      await studentPage
        .getByRole("button", { name: "Enable optional history" })
        .click();
      await expect(
        studentPage.getByText("Optional history is on for this session."),
      ).toBeVisible();
    } else {
      await studentPage
        .getByRole("button", { name: "Continue without history" })
        .click();
      await expect(
        studentPage.getByText("Optional history is off."),
      ).toBeVisible();
    }
    await studentPage
      .getByRole("radio", { name: /Alpha/ })
      .check({ timeout: 30000 });
    await studentPage
      .getByRole("radio", { name: /Gamma/ })
      .check({ timeout: 30000 });
    await expect(
      studentPage.getByRole("radio", { name: /Gamma/ }),
    ).toBeChecked();
    // Core submission uses the real UI and its authoritative coordinator, not the observation queue.
    await studentPage.getByRole("button", { name: "Submit attempt" }).click();
    await studentPage
      .getByRole("button", { name: "Submit complete answers" })
      .click();
    await expect(studentPage).toHaveURL(
      new RegExp(`/app/attempts/${attempt}/result`),
    );
    const review = await teacher.request(`${base}/teacher-review`);
    expect(review.status).toBe(200);
    expect(review.cache).toBe("no-store");
    expect(
      review.body.state === "SUBMITTED" &&
        review.body.questions[0].correctAnswer.optionLabel === "C" &&
        review.body.questions[0].answer.optionLabel === "C",
    ).toBe(true);
    await teacherPage.goto(
      `/app/assessments/${pub}/attempts/${attempt}/review`,
    );
    await teacherPage
      .getByRole("button", { name: "Read submitted work" })
      .click();
    await expect(
      teacherPage.getByText("Submitted answer", { exact: true }),
    ).toBeVisible();
    await teacherPage
      .getByRole("button", { name: "Read interaction timeline" })
      .click();
    await expect(
      teacherPage.getByRole("heading", { name: "Interaction timeline" }),
    ).toBeVisible();
    for (const width of [375, 768, 1280, 1920]) {
      await teacherPage.setViewportSize({ width, height: 900 });
      expect(
        await teacherPage.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
    }
    const timeline = await teacher.request(`${base}/answer-audit`);
    expect(timeline.status).toBe(200);
    expect(
      timeline.body.items.some(
        (e: { type: string; source: string }) =>
          e.type === "ATTEMPT_STARTED" && e.source === "SERVER_AUTHORITY",
      ),
    ).toBe(true);
    expect(
      timeline.body.items.some(
        (e: { type: string }) => e.type === "RESULT_GRADED",
      ),
    ).toBe(true);
    const observations = timeline.body.items.filter(
      (e: { source: string }) => e.source === "CLIENT_OBSERVATION",
    );
    if (audience === "PUBLIC")
      expect(
        observations.some(
          (e: {
            type: string;
            transition?: {
              fromOptionLabels: string[];
              toOptionLabels: string[];
            };
          }) =>
            e.type === "ANSWER_CHANGED" &&
            e.transition?.fromOptionLabels[0] === "A" &&
            e.transition.toOptionLabels[0] === "C",
        ),
      ).toBe(true);
    else expect(observations.length).toBe(0);
    expect(
      (await student.request(`/api/assessments/attempts/${attempt}/review`))
        .status,
    ).toBe(409);
    expect((await student.request(`${base}/teacher-review`)).status).toBe(403);
    expect(
      (
        await teacher.request(
          `/api/assessments/publications/${crypto.randomUUID()}/attempts/${attempt}/teacher-review`,
        )
      ).status,
    ).toBe(404);
    const retake = await student.request(
      `/api/assessments/publications/${pub}/attempts/retake`,
      "POST",
      audience === "PUBLIC" ? { locator: opened.body.privateLocator } : {},
      crypto.randomUUID(),
    );
    expect([200, 201].includes(retake.status)).toBe(true);
    expect(
      (
        await teacher.request(
          `/api/assessments/publications/${pub}/attempts/${retake.body.attemptId}/teacher-review`,
        )
      ).status,
    ).toBe(409);
    const abandoned = await student.request(
      `/api/assessments/attempts/${retake.body.attemptId}/abandon`,
      "POST",
      { expectedRevision: retake.body.revision },
      crypto.randomUUID(),
    );
    expect(abandoned.status).toBe(200);
    const current = await teacher.request(
      `/api/assessments/publications/${pub}`,
    );
    expect(
      (
        await teacher.request(
          `/api/assessments/publications/${pub}/close`,
          "POST",
          { expectedRevision: current.body.revision },
          crypto.randomUUID(),
        )
      ).status,
    ).toBe(200);
    expect(
      (await student.request(`/api/assessments/attempts/${attempt}/review`))
        .status,
    ).toBe(200);

    // W4F-T3 unified dashboard journey on the real stack: the teacher opens
    // the student-centric results route and sees server aggregates — distinct
    // counters, the exact 20-bin histogram with the single 10.00 best score,
    // one directory row with two attempts (graded submission + abandoned
    // retake) and gated review/evidence links.
    await teacherPage.goto(`/app/assessments/${pub}/results`);
    // The results workspace belongs to Teaching; switch once if this actor
    // still sits in the default Learning persona, then wait for the dashboard.
    const switchToTeaching = teacherPage.getByRole("button", {
      name: "Switch to Teaching workspace",
    });
    await switchToTeaching
      .waitFor({ state: "visible", timeout: 10_000 })
      .then(() => switchToTeaching.click())
      .catch(() => {});
    await expect(
      teacherPage.getByRole("heading", { name: `Fictional ${audience} demo` }),
    ).toBeVisible({ timeout: 20_000 });
    await expect(teacherPage.getByText("Total participants")).toBeVisible();
    const scoreTable = teacherPage.getByRole("region", {
      name: "Score distribution table",
    });
    await expect(scoreTable).toBeVisible();
    await expect(
      scoreTable.getByRole("row", { name: /9\.5 – 10\s*1/ }),
    ).toBeVisible();
    await expect(teacherPage.getByText("100.00%").first()).toBeVisible();
    await teacherPage.getByRole("button", { name: "View attempts" }).click();
    const history = teacherPage.getByRole("region", {
      name: "Attempt history table",
    });
    await expect(history).toBeVisible();
    await expect(history.getByText("Best attempt")).toBeVisible();
    await expect(
      history.getByRole("link", { name: "Review submitted work" }),
    ).toBeVisible();
    await expect(history.getByText("Student abandoned")).toBeVisible();
    for (const width of [375, 768, 1280, 1920]) {
      await teacherPage.setViewportSize({ width, height: 900 });
      expect(
        await teacherPage.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
    }
  }
  expect(pubIds.length).toBe(2);
  await teacherPage.close();
  await studentPage.close();
});
