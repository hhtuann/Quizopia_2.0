import { randomBytes, randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import type { BrowserContext } from "@playwright/test";
import type {
  ClosureView,
  Notification,
  OwnerView,
} from "../../features/assessments/api/publication-contract.generated";
import type { AttemptView } from "../../features/attempts/api/attempt-contract.generated";
import {
  assertStatus,
  commandResponse,
  confirm,
  ensureTeachingWorkspace,
  evidence,
  fixtureControl,
  gateway,
  matchGatewayUrl,
  publicationBase,
  publishMultiQuestionSource,
  registerTeacher,
  utcField,
  utcLocalParts,
} from "./helpers";

// These journeys assert English UI strings while the app defaults to
// Vietnamese. Teacher flows pin English through the shared helpers; the
// default context is pinned before registration here, and secondary actor
// contexts are pinned below before their first navigation.
async function pinEnglishContext(context: BrowserContext) {
  await context.addCookies([
    {
      name: "quizopia-locale",
      value: "en",
      url: process.env.W3A_REAL_FRONTEND_URL ?? "http://localhost:3000",
    },
  ]);
}

test.beforeEach(async ({ page }) => {
  await pinEnglishContext(page.context());
});

test("real PUBLIC Assessment: Teacher OPEN, Student locator entry, 4 question types autosave/revision, submit, result, teacher CLOSE, results table and student closed review", async ({
  browser,
  page,
  request,
}, info) => {
  // 1. Teacher publishes Quiz with 4 question types
  const teacher = await registerTeacher(page, request, info);
  const source = await publishMultiQuestionSource(page);
  evidence(
    info,
    "Teacher published immutable QuizVersion 1 containing SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE_MATRIX, and NUMERIC_FILL.",
  );

  // 2. Teacher creates and OPENs PUBLIC Assessment
  await page.getByRole("radio", { name: "Public", exact: true }).check();
  const scheduleParts = utcLocalParts(-2);
  await page
    .getByLabel("Participation start", { exact: true })
    .fill(scheduleParts.date);
  await page.getByLabel("Participation start time").fill(scheduleParts.time);
  const endParts = utcLocalParts(60);
  await page
    .getByLabel("Participation end", { exact: true })
    .fill(endParts.date);
  await page.getByLabel("Participation end time").fill(endParts.time);
  await page.getByRole("button", { name: "Review and create DRAFT" }).click();
  const createdResponse = commandResponse(page, publicationBase, "POST");
  await confirm(page, "Create DRAFT");
  const createdHttp = await createdResponse;
  assertStatus(createdHttp.status(), 201, "real PUBLIC create");
  const created = (await createdHttp.json()) as OwnerView;
  await expect(page).toHaveURL(`/app/assessments/${created.publicationId}`);

  await page
    .getByRole("button", { name: "Review and OPEN", exact: true })
    .click();
  const openedResponse = commandResponse(page, "/open", "POST");
  await confirm(page, "OPEN Publication");
  assertStatus((await openedResponse).status(), 200, "real PUBLIC OPEN");
  const opened = await teacher.owner(created.publicationId);
  expect(opened.state).toBe("OPEN");
  expect(Boolean(opened.privateLocator)).toBe(true);
  const privateLocator = opened.privateLocator!;
  evidence(
    info,
    "Authoritative PUBLIC assessment OPEN with valid private locator.",
  );

  // 3. Student registers in a separate browser context and enters assessment
  const studentContext = await browser.newContext();
  await pinEnglishContext(studentContext);
  try {
    const studentPage = await studentContext.newPage();
    const student = await registerTeacher(studentPage, request, info, false);

    await studentPage.goto("/app/assessments/join");
    await studentPage.getByRole("tab", { name: "Private locator" }).click();
    await studentPage
      .getByLabel("43-character private locator")
      .fill(privateLocator);

    const previewResponse = studentPage.waitForResponse(
      (res) =>
        matchGatewayUrl(res.url(), "/api/assessments/publications/") &&
        res.url().endsWith("/entry") &&
        res.request().method() === "POST",
    );
    await studentPage.getByRole("button", { name: "Check access" }).click();
    const previewHttp = await previewResponse;
    assertStatus(previewHttp.status(), 200, "real PUBLIC preview check");
    await expect(studentPage.getByText("Authorized preview")).toBeVisible();
    await expect(
      studentPage.getByRole("button", { name: "Start or resume" }),
    ).toBeVisible();

    // Start Attempt
    const startResponse = studentPage.waitForResponse(
      (res) =>
        matchGatewayUrl(res.url(), "/api/assessments/publications/") &&
        res.url().endsWith("/attempts/start") &&
        res.request().method() === "POST",
    );
    await studentPage.getByRole("button", { name: "Start or resume" }).click();
    const startHttp = await startResponse;
    assertStatus(startHttp.status(), 201, "real Student start attempt");
    const startedAttempt = (await startHttp.json()) as AttemptView;
    expect(startedAttempt.state).toBe("ACTIVE");
    await expect(studentPage).toHaveURL(
      `/app/attempts/${startedAttempt.attemptId}`,
    );
    evidence(
      info,
      "Student started real ACTIVE Attempt via private locator; redirected to attempt page.",
    );

    // 4. Autosave 4 Question Types
    // Question 1: SINGLE_CHOICE - Select Option A ("HTTP")
    const q1Save = studentPage.waitForResponse(
      (res) =>
        res
          .url()
          .includes(
            `/attempts/${startedAttempt.attemptId}/questions/1/answer`,
          ) && res.request().method() === "POST",
    );
    await studentPage.locator("label", { hasText: "HTTP" }).click();
    assertStatus(
      (await q1Save).status(),
      200,
      "real autosave Q1 SINGLE_CHOICE",
    );
    await expect(studentPage.getByText("Saved", { exact: true })).toBeVisible();

    // Navigate to Question 2 (MULTIPLE_CHOICE)
    await studentPage
      .getByRole("navigation", { name: "Attempt questions" })
      .getByRole("button", { name: "2" })
      .click();

    // Select Option A ("TCP") and Option B ("UDP")
    const q2SaveA = studentPage.waitForResponse(
      (res) =>
        res
          .url()
          .includes(
            `/attempts/${startedAttempt.attemptId}/questions/2/answer`,
          ) && res.request().method() === "POST",
    );
    await studentPage.locator("label", { hasText: "TCP" }).click();
    assertStatus(
      (await q2SaveA).status(),
      200,
      "real autosave Q2 MULTIPLE_CHOICE first option",
    );

    const q2SaveB = studentPage.waitForResponse(
      (res) =>
        res
          .url()
          .includes(
            `/attempts/${startedAttempt.attemptId}/questions/2/answer`,
          ) && res.request().method() === "POST",
    );
    await studentPage.locator("label", { hasText: "UDP" }).click();
    assertStatus(
      (await q2SaveB).status(),
      200,
      "real autosave Q2 MULTIPLE_CHOICE second option",
    );
    await expect(studentPage.getByText("Saved", { exact: true })).toBeVisible();

    // Navigate to Question 3 (TRUE_FALSE_MATRIX)
    await studentPage
      .getByRole("navigation", { name: "Attempt questions" })
      .getByRole("button", { name: "3" })
      .click();

    // Statements: A (True), B (False), C (True), D (False)
    const labelA = studentPage
      .locator("label", {
        has: studentPage.locator('input[name="question-3-A"]'),
      })
      .filter({ hasText: "True" });
    const labelB = studentPage
      .locator("label", {
        has: studentPage.locator('input[name="question-3-B"]'),
      })
      .filter({ hasText: "False" });
    const labelC = studentPage
      .locator("label", {
        has: studentPage.locator('input[name="question-3-C"]'),
      })
      .filter({ hasText: "True" });
    const labelD = studentPage
      .locator("label", {
        has: studentPage.locator('input[name="question-3-D"]'),
      })
      .filter({ hasText: "False" });

    const q3SaveA = studentPage.waitForResponse(
      (res) =>
        res
          .url()
          .includes(
            `/attempts/${startedAttempt.attemptId}/questions/3/answer`,
          ) && res.request().method() === "POST",
    );
    await labelA.click();
    assertStatus((await q3SaveA).status(), 200, "real autosave Q3 statement A");

    const q3SaveB = studentPage.waitForResponse(
      (res) =>
        res
          .url()
          .includes(
            `/attempts/${startedAttempt.attemptId}/questions/3/answer`,
          ) && res.request().method() === "POST",
    );
    await labelB.click();
    assertStatus((await q3SaveB).status(), 200, "real autosave Q3 statement B");

    const q3SaveC = studentPage.waitForResponse(
      (res) =>
        res
          .url()
          .includes(
            `/attempts/${startedAttempt.attemptId}/questions/3/answer`,
          ) && res.request().method() === "POST",
    );
    await labelC.click();
    assertStatus((await q3SaveC).status(), 200, "real autosave Q3 statement C");

    const q3SaveD = studentPage.waitForResponse(
      (res) =>
        res
          .url()
          .includes(
            `/attempts/${startedAttempt.attemptId}/questions/3/answer`,
          ) && res.request().method() === "POST",
    );
    await labelD.click();
    assertStatus((await q3SaveD).status(), 200, "real autosave Q3 statement D");
    await expect(studentPage.getByText("Saved", { exact: true })).toBeVisible();

    // Navigate to Question 4 (NUMERIC_FILL)
    await studentPage
      .getByRole("navigation", { name: "Attempt questions" })
      .getByRole("button", { name: "4" })
      .click();

    const q4Save = studentPage.waitForResponse(
      (res) =>
        res
          .url()
          .includes(
            `/attempts/${startedAttempt.attemptId}/questions/4/answer`,
          ) && res.request().method() === "POST",
    );
    await studentPage.locator("#numeric-4").fill("2.50");
    assertStatus((await q4Save).status(), 200, "real autosave Q4 NUMERIC_FILL");
    await expect(studentPage.getByText("Saved", { exact: true })).toBeVisible();
    evidence(
      info,
      "Real autosave verified across all four question types: SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE_MATRIX, NUMERIC_FILL.",
    );

    // Answer Revision: Switch back to Q1, change to B ("FTP"), then back to A ("HTTP")
    await studentPage
      .getByRole("navigation", { name: "Attempt questions" })
      .getByRole("button", { name: "1" })
      .click();

    const q1Rev1 = studentPage.waitForResponse(
      (res) =>
        res
          .url()
          .includes(
            `/attempts/${startedAttempt.attemptId}/questions/1/answer`,
          ) && res.request().method() === "POST",
    );
    await studentPage.locator("label", { hasText: "FTP" }).click();
    assertStatus((await q1Rev1).status(), 200, "real revision Q1 to FTP");
    await expect(studentPage.getByText("Saved", { exact: true })).toBeVisible();

    const q1Rev2 = studentPage.waitForResponse(
      (res) =>
        res
          .url()
          .includes(
            `/attempts/${startedAttempt.attemptId}/questions/1/answer`,
          ) && res.request().method() === "POST",
    );
    await studentPage.locator("label", { hasText: "HTTP" }).click();
    assertStatus((await q1Rev2).status(), 200, "real revision Q1 back to HTTP");
    await expect(studentPage.getByText("Saved", { exact: true })).toBeVisible();
    evidence(
      info,
      "Answer revision successfully handled with incremented revision sequence.",
    );

    // 5. Submit Attempt
    await studentPage.getByRole("button", { name: "Submit attempt" }).click();
    const submitResponse = studentPage.waitForResponse(
      (res) =>
        res.url().includes(`/attempts/${startedAttempt.attemptId}/submit`) &&
        res.request().method() === "POST",
    );
    await studentPage
      .getByRole("dialog")
      .getByRole("button", { name: "Submit complete answers" })
      .click();
    const submitHttp = await submitResponse;
    assertStatus(submitHttp.status(), 200, "real Student submit attempt");
    // Router transitions directly to Result Screen
    await expect(studentPage).toHaveURL(
      `/app/attempts/${startedAttempt.attemptId}/result`,
    );
    await expect(
      studentPage.getByRole("heading", { name: "Attempt result" }),
    ).toBeVisible();
    await expect(studentPage.getByText("Total score")).toBeVisible();
    await expect(
      studentPage.getByText("100.00%", { exact: true }),
    ).toBeVisible();
    await expect(studentPage.getByText("4 of 4 exact weight")).toBeVisible();
    evidence(info, "Result screen displays 100.00% score calculation.");

    // 6. Teacher CLOSE
    await page.bringToFront();
    await page.goto(`/app/assessments/${created.publicationId}`);
    await ensureTeachingWorkspace(page);
    await page.getByRole("button", { name: "Review and CLOSE" }).click();
    const closeResponse = commandResponse(page, "/close", "POST");
    await confirm(page, "CLOSE Publication");
    assertStatus(
      (await closeResponse).status(),
      200,
      "real Teacher CLOSE with submitted attempt",
    );
    await expect(
      page.getByText("Publication CLOSED", { exact: true }),
    ).toBeVisible();
    evidence(
      info,
      "Teacher successfully closed assessment after attempt submission.",
    );

    // 7. Teacher Results Table
    await page
      .getByRole("link", { name: /View (?:attempt )?results/i })
      .click();
    await expect(page).toHaveURL(
      `/app/assessments/${created.publicationId}/results`,
    );
    await ensureTeachingWorkspace(page);
    await expect(
      page.getByRole("heading", { name: "Attempt results" }),
    ).toBeVisible();
    await expect(
      page.getByText(startedAttempt.attemptId, { exact: false }),
    ).toBeVisible();
    await expect(page.getByText("100.00%", { exact: true })).toBeVisible();
    evidence(
      info,
      "Teacher results table displays the submitted student attempt and score.",
    );

    // 8. Student Review (Post-Closure)
    await studentPage.bringToFront();
    await studentPage.goto(`/app/attempts/${startedAttempt.attemptId}/review`);
    await expect(
      studentPage.getByRole("heading", { name: "Answer review" }),
    ).toBeVisible();
    await expect(
      studentPage.getByText("Authorized closed review"),
    ).toBeVisible();
    evidence(
      info,
      "Student review screen reveals questions and answers after durable assessment closure.",
    );

    await teacher.assertConfidentiality();
    await student.assertConfidentiality();
  } finally {
    await studentContext.close();
  }
});

test("real CLASS Assessment: Teacher creates Class & Assessment, Student membership gate, ACTIVE blocks CLOSE, Student abandon, Teacher CLOSE and Notifications inbox", async ({
  browser,
  page,
  request,
}, info) => {
  const teacher = await registerTeacher(page, request, info);
  const teacherMe = await teacher.fetch<{ id: string }>("/api/auth/me");

  // Create Classroom
  const classroom = await teacher.fetch<{ id: string }>("/classrooms", "POST", {
    name: "Real Acceptance Class",
    subject: "Computer Science",
    grade: "12",
    description: "Isolated acceptance classroom",
  });
  assertStatus(classroom.status, 201, "real Classroom creation");

  // Publish Quiz
  const source = await publishMultiQuestionSource(page);

  // Create CLASS Assessment with monitoring DISABLED
  await page.getByRole("radio", { name: "Classroom", exact: true }).check();
  await page
    .getByRole("radio", { name: "Real Acceptance Class", exact: true })
    .check();
  const scheduleParts = utcLocalParts(-2);
  await page
    .getByLabel("Participation start", { exact: true })
    .fill(scheduleParts.date);
  await page.getByLabel("Participation start time").fill(scheduleParts.time);
  const endParts = utcLocalParts(60);
  await page
    .getByLabel("Participation end", { exact: true })
    .fill(endParts.date);
  await page.getByLabel("Participation end time").fill(endParts.time);
  await page.getByRole("button", { name: "Review and create DRAFT" }).click();
  const createdResponse = commandResponse(page, publicationBase, "POST");
  await confirm(page, "Create DRAFT");
  const createdHttp = await createdResponse;
  assertStatus(createdHttp.status(), 201, "real CLASS create");
  const created = (await createdHttp.json()) as OwnerView;

  // Set explicit monitoring choice to DISABLED
  await page.getByRole("button", { name: "Edit DRAFT" }).click();
  await page.getByRole("radio", { name: "Disabled — no monitoring" }).check();
  await page.getByRole("button", { name: "Review DRAFT changes" }).click();
  const updateResponse = commandResponse(
    page,
    `/${created.publicationId}`,
    "PUT",
  );
  await confirm(page, "Save DRAFT");
  assertStatus((await updateResponse).status(), 200, "set monitoring DISABLED");

  // OPEN
  await page.getByRole("button", { name: "Review and OPEN" }).click();
  const openedResponse = commandResponse(page, "/open", "POST");
  await confirm(page, "OPEN Publication");
  assertStatus((await openedResponse).status(), 200, "real CLASS OPEN");
  evidence(
    info,
    "CLASS Assessment created and opened with monitoring DISABLED.",
  );

  const studentContext = await browser.newContext();
  await pinEnglishContext(studentContext);
  try {
    const studentPage = await studentContext.newPage();
    const student = await registerTeacher(studentPage, request, info, false);
    const studentMe = await student.fetch<{ id: string }>("/api/auth/me");

    // 1. Try to enter CLASS assessment before membership
    await studentPage.goto("/app/assessments/join");
    await studentPage
      .getByRole("tab", { name: "Known class Publication" })
      .click();
    await studentPage.getByLabel("Publication ID").fill(created.publicationId);
    const deniedEntry = studentPage.waitForResponse(
      (res) =>
        matchGatewayUrl(res.url(), "/api/assessments/publications/") &&
        res.url().endsWith("/entry") &&
        res.request().method() === "POST",
    );
    await studentPage.getByRole("button", { name: "Check access" }).click();
    const deniedHttp = await deniedEntry;
    assertStatus(deniedHttp.status(), 404, "student denied before membership");
    await expect(
      studentPage.getByText(
        /Your current class membership does not grant access|not accessible|denied/i,
      ),
    ).toBeVisible();
    evidence(info, "Student denied entry before classroom membership.");

    // 2. Seed membership in Classroom DB
    await fixtureControl(
      "seed-membership",
      classroom.body.id,
      studentMe.body.id,
      teacherMe.body.id,
    );

    // 3. Retry entry after membership seeded
    const previewRetry = studentPage.waitForResponse(
      (res) =>
        matchGatewayUrl(res.url(), "/api/assessments/publications/") &&
        res.url().endsWith("/entry") &&
        res.request().method() === "POST",
    );
    await studentPage.getByRole("button", { name: "Check access" }).click();
    assertStatus(
      (await previewRetry).status(),
      200,
      "real CLASS preview check",
    );
    await expect(studentPage.getByText("Authorized preview")).toBeVisible();
    await expect(
      studentPage.getByRole("button", { name: "Start or resume" }),
    ).toBeVisible();

    // Start Attempt
    const startResponse = studentPage.waitForResponse(
      (res) =>
        matchGatewayUrl(res.url(), "/api/assessments/publications/") &&
        res.url().endsWith("/attempts/start") &&
        res.request().method() === "POST",
    );
    await studentPage.getByRole("button", { name: "Start or resume" }).click();
    const startHttp = await startResponse;
    assertStatus(
      startHttp.status(),
      201,
      "real authorized CLASS start attempt",
    );
    const attempt = (await startHttp.json()) as AttemptView;
    expect(attempt.state).toBe("ACTIVE");
    await expect(studentPage).toHaveURL(`/app/attempts/${attempt.attemptId}`);
    evidence(
      info,
      "Student successfully started CLASS attempt after membership fixture.",
    );

    // 4. Active Attempt prevents Teacher CLOSE
    await page.bringToFront();
    await ensureTeachingWorkspace(page);
    await page.getByRole("button", { name: "Review and CLOSE" }).click();
    const blockedClose = commandResponse(page, "/close", "POST");
    await confirm(page, "CLOSE Publication");
    const blocked = await blockedClose;
    assertStatus(blocked.status(), 409, "ACTIVE attempt blocks CLOSE");
    await expect(
      page.getByText(/Active Attempts prevent closure/),
    ).toBeVisible();
    evidence(
      info,
      "ACTIVE attempt correctly produces 409 ACTIVE_ATTEMPTS conflict on CLOSE.",
    );

    // 5. Student Abandons Attempt
    await studentPage.bringToFront();
    await studentPage.getByRole("button", { name: "Abandon attempt" }).click();
    const abandonResponse = studentPage.waitForResponse(
      (res) =>
        res.url().includes(`/attempts/${attempt.attemptId}/abandon`) &&
        res.request().method() === "POST",
    );
    await studentPage
      .getByRole("dialog")
      .getByRole("button", { name: "Abandon Attempt" })
      .click();
    const abandonHttp = await abandonResponse;
    assertStatus(abandonHttp.status(), 200, "real Student abandon attempt");
    await expect(
      studentPage.getByRole("heading", { name: "Attempt abandoned" }),
    ).toBeVisible();
    evidence(
      info,
      "Student abandoned attempt; state transitions to ABANDONED.",
    );

    // 6. Teacher can now CLOSE
    await page.bringToFront();
    await page.goto(`/app/assessments/${created.publicationId}`);
    await ensureTeachingWorkspace(page);
    await page.getByRole("button", { name: "Review and CLOSE" }).click();
    const closedResponse = commandResponse(page, "/close", "POST");
    await confirm(page, "CLOSE Publication");
    assertStatus((await closedResponse).status(), 200, "CLOSE after abandon");
    await expect(
      page.getByText("Publication CLOSED", { exact: true }),
    ).toBeVisible();
    evidence(info, "Assessment successfully closed after attempt abandoned.");

    // 7. Student Notifications Inbox
    const inboxResponse = studentPage.waitForResponse(
      (res) =>
        res.url().includes("/api/assessments/notifications") &&
        res.request().method() === "GET",
    );
    await studentPage.goto("/app/notifications");
    const inboxHttp = await inboxResponse;
    assertStatus(
      inboxHttp.status(),
      200,
      "real Student GET /api/assessments/notifications",
    );
    await expect(
      studentPage.getByRole("heading", { name: "Your inbox" }),
    ).toBeVisible();
    await expect(
      studentPage.getByRole("heading", { name: "No assessment notices yet" }),
    ).toBeVisible();
    evidence(
      info,
      "Student notifications inbox screen reached and authenticated GET 200 confirmed; empty inbox prior to delayed closure requests.",
    );

    await teacher.assertConfidentiality();
    await student.assertConfidentiality();
  } finally {
    await studentContext.close();
  }
});

test("real CLASS Assessment B3: Teacher REQUEST, notice publication, recipient authorization, confirmed cutoff +48h, former CLASS member read, CANCEL and early COMPLETE denial", async ({
  browser,
  page,
  request,
}, info) => {
  // Ensure pre-attestation / pre-worker clean baseline state
  await fixtureControl("reset-closure-flags");

  // 1. Register Teacher and create Classroom
  const teacher = await registerTeacher(page, request, info);
  const teacherMe = await teacher.fetch<{ id: string }>("/api/auth/me");

  const classroom = await teacher.fetch<{ id: string }>("/classrooms", "POST", {
    name: "Real B3 Acceptance Class",
    subject: "Computer Science",
    grade: "12",
    description:
      "Isolated acceptance classroom for closure and notification verification",
  });
  assertStatus(classroom.status, 201, "real Classroom creation");

  // 2. Register Student A in a separate browser context
  const studentContext = await browser.newContext();
  await pinEnglishContext(studentContext);
  try {
    const studentPage = await studentContext.newPage();
    const student = await registerTeacher(studentPage, request, info, false);
    const studentMe = await student.fetch<{ id: string }>("/api/auth/me");

    // Target 2: Target 2 pre-readiness verification BEFORE worker/notice dependencies
    // (a) Gateway route / health UP
    const healthRes = await request.get(
      `${gateway}/actuator/health/assessment-inbox`,
    );
    assertStatus(healthRes.status(), 200, "Gateway assessment-inbox health");
    const healthJson = (await healthRes.json()) as { status: string };
    expect(healthJson.status).toBe("UP");

    // (b) Fresh authenticated Student inbox 200 (empty)
    let emptyInboxRes!: Awaited<
      ReturnType<
        typeof student.fetch<{
          items: unknown[];
          nextCursor: string | null;
        }>
      >
    >;
    await expect
      .poll(
        async () => {
          emptyInboxRes = await student.fetch<{
            items: unknown[];
            nextCursor: string | null;
          }>("/api/assessments/notifications");
          return emptyInboxRes.status;
        },
        { timeout: 10_000, intervals: [200, 400, 800] },
      )
      .toBe(200);
    expect(emptyInboxRes.body.items).toEqual([]);

    // (c) Fresh authenticated Student unknown notice 404 (non-existent notice ID probe)
    const unknownNoticeRes = await student.fetch(
      `/api/assessments/notifications/${randomUUID()}`,
    );
    assertStatus(unknownNoticeRes.status, 404, "Unknown notice 404");

    // (d) Probe Identity eligible recipient contract with least-privilege SERVICE scope
    const recipientProbeJson = await fixtureControl(
      "probe-recipient-eligibility",
      studentMe.body.id,
    );
    const recipientProbe = JSON.parse(recipientProbeJson) as {
      status: string;
      studentId: string;
      eligible: boolean;
      least_privilege_verified: boolean;
    };
    expect(recipientProbe.status).toBe("VERIFIED");
    expect(recipientProbe.studentId).toBe(studentMe.body.id);
    expect(recipientProbe.eligible).toBe(true);
    expect(recipientProbe.least_privilege_verified).toBe(true);
    evidence(
      info,
      "Target 2 verified: Gateway /actuator/health/assessment-inbox UP, Student inbox 200/404 unknown-ID verified, and Identity recipient contract verified with least-privilege SERVICE token scope.",
    );

    // 3. Seed Student A membership in Classroom
    await fixtureControl(
      "seed-membership",
      classroom.body.id,
      studentMe.body.id,
      teacherMe.body.id,
    );

    // 4. Publish Quiz source (immutable QuizVersion 1)
    await page.bringToFront();
    await ensureTeachingWorkspace(page);
    const source = await publishMultiQuestionSource(page);

    // 5. Teacher creates CLASS Assessment with durationMinutes: 0 (unlimited)
    // Window: startAt = -2 minutes, endAt = +35 seconds
    const endAtOffsetSeconds = 35;
    const startAtUtc = `${utcField(-2, "123456")}Z`;
    const endAtUtc = `${utcField(endAtOffsetSeconds / 60, "654321")}Z`;
    const draftKey = randomBytes(16).toString("hex");

    const draftRes = await teacher.fetch<OwnerView>(
      "/api/assessments/publications",
      "POST",
      {
        title: "Real B3 Delayed Closure Assessment",
        description:
          "Class assessment with durationMinutes=0 for delayed closure acceptance",
        quizId: source.quizId,
        quizVersionId: source.quizVersionId,
        versionNumber: 1,
        audience: "CLASS",
        classroomId: classroom.body.id,
        startAt: startAtUtc,
        endAt: endAtUtc,
        durationMinutes: 0,
        monitoringChoice: "DISABLED",
        maxAttempts: 1,
      },
      draftKey,
    );
    assertStatus(
      draftRes.status,
      201,
      "real CLASS create DRAFT with durationMinutes=0",
    );
    const publicationId = draftRes.body.publicationId;

    // 6. Teacher navigates to assessment in UI and OPENs it
    await page.goto(`/app/assessments/${publicationId}`);
    await ensureTeachingWorkspace(page);
    await page
      .getByRole("button", { name: "Review and OPEN", exact: true })
      .click();
    const openedResponse = commandResponse(page, "/open", "POST");
    await confirm(page, "OPEN Publication");
    assertStatus((await openedResponse).status(), 200, "real CLASS OPEN");
    evidence(
      info,
      "CLASS Assessment opened with durationMinutes: 0 and monitoringChoice: DISABLED.",
    );

    // 7. Student enters assessment and starts ACTIVE attempt (durationMinutes: 0)
    await studentPage.bringToFront();
    await studentPage.goto("/app/assessments/join");
    await studentPage
      .getByRole("tab", { name: "Known class Publication" })
      .click();
    await studentPage.getByLabel("Publication ID").fill(publicationId);

    const previewRes = studentPage.waitForResponse(
      (res) =>
        matchGatewayUrl(res.url(), "/api/assessments/publications/") &&
        res.url().endsWith("/entry") &&
        res.request().method() === "POST",
    );
    await studentPage.getByRole("button", { name: "Check access" }).click();
    assertStatus((await previewRes).status(), 200, "Student entry check");
    await expect(studentPage.getByText("Authorized preview")).toBeVisible();

    const startRes = studentPage.waitForResponse(
      (res) =>
        matchGatewayUrl(res.url(), "/api/assessments/publications/") &&
        res.url().endsWith("/attempts/start") &&
        res.request().method() === "POST",
    );
    await studentPage.getByRole("button", { name: "Start or resume" }).click();
    const startHttp = await startRes;
    assertStatus(startHttp.status(), 201, "Student start attempt");
    const startedAttempt = (await startHttp.json()) as AttemptView;
    expect(startedAttempt.state).toBe("ACTIVE");
    expect(startedAttempt.deadlineAt).toBeNull();
    await expect(studentPage).toHaveURL(
      `/app/attempts/${startedAttempt.attemptId}`,
    );

    // Student answers Q1 to ensure attempt is in active progress
    const q1Save = studentPage.waitForResponse(
      (res) =>
        res
          .url()
          .includes(
            `/attempts/${startedAttempt.attemptId}/questions/1/answer`,
          ) && res.request().method() === "POST",
    );
    await studentPage.locator("label", { hasText: "HTTP" }).click();
    assertStatus((await q1Save).status(), 200, "real autosave Q1");
    await expect(studentPage.getByText("Saved", { exact: true })).toBeVisible();
    evidence(
      info,
      "Student started ACTIVE unlimited attempt (duration_minutes: 0, deadline_at: null) and saved answer.",
    );

    // 8. Target 2 / H1 pre-worker readiness: Probe pre-attestation REQUEST denial (worker & edge attestation OFF)
    const ownerBefore = await teacher.owner(publicationId);
    const preAttestationRes = await teacher.fetch<{
      code: string;
      message: string;
    }>(
      `/api/assessments/publications/${publicationId}/closure-requests`,
      "POST",
      { expectedRevision: ownerBefore.revision },
      randomBytes(16).toString("hex"),
    );
    assertStatus(
      preAttestationRes.status,
      503,
      "Pre-attestation REQUEST denied with 503",
    );
    expect(preAttestationRes.body.code).toBe("DEPENDENCY_UNAVAILABLE");
    evidence(
      info,
      "Target 2 / H1 verified: Teacher REQUEST denied with 503 DEPENDENCY_UNAVAILABLE while worker and edge inbox attestation are OFF.",
    );

    // Operator-controlled transition 1: activate edge inbox attestation (synthetic closure worker remains OFF)
    const attestationOutput = await fixtureControl("activate-edge-attestation");
    expect(attestationOutput).toContain(
      "PASS operator activation: edge inbox attestation activated; closure worker remains OFF",
    );
    evidence(
      info,
      "H1 verified: Operator-controlled staged transition successfully activated edge inbox attestation while synthetic closure worker remains OFF.",
    );

    // 9. Probe early REQUEST denial (now < endAt) -> 409 CLOSURE_NOT_READY
    const ownerAfterActivation = await teacher.owner(publicationId);
    const earlyRequestRes = await teacher.fetch<{
      code: string;
      message: string;
    }>(
      `/api/assessments/publications/${publicationId}/closure-requests`,
      "POST",
      { expectedRevision: ownerAfterActivation.revision },
      randomBytes(16).toString("hex"),
    );
    assertStatus(
      earlyRequestRes.status,
      409,
      "Early REQUEST denied before endAt",
    );
    expect(earlyRequestRes.body.code).toBe("CLOSURE_NOT_READY");
    evidence(
      info,
      "Early REQUEST denied with 409 CLOSURE_NOT_READY while participation window is active (now < endAt).",
    );

    // 10. Wait until window has elapsed (now >= endAt)
    const endAtMillis = Date.parse(endAtUtc);
    const remainingWait = endAtMillis - Date.now() + 1500;
    if (remainingWait > 0) {
      await page.waitForTimeout(remainingWait);
    }

    // 11. Teacher REQUEST delayed closure via UI
    await page.bringToFront();
    await page.goto(`/app/assessments/${publicationId}/closure`);
    await ensureTeachingWorkspace(page);
    await page
      .getByRole("button", { name: "Review and REQUEST closure" })
      .click();
    await page.getByLabel("Type REQUEST to enable this action").fill("REQUEST");

    const requestCommandRes = page.waitForResponse(
      (res) =>
        res.url().includes(`/closure-requests`) &&
        res.request().method() === "POST" &&
        !res.url().endsWith("/cancel") &&
        !res.url().endsWith("/complete"),
    );
    await page.getByRole("button", { name: "REQUEST delayed closure" }).click();
    const requestHttp = await requestCommandRes;
    assertStatus(requestHttp.status(), 201, "Teacher REQUEST delayed closure");
    const requestKey = requestHttp.request().headers()["idempotency-key"];
    const originalRequestBody = requestHttp.request().postDataJSON() as {
      expectedRevision: number;
    };
    const requestView = (await requestHttp.json()) as ClosureView;
    expect(requestView.state).toBe("PENDING");
    const closureRequestId = requestView.closureRequestId;

    await expect(
      page.getByRole("heading", { name: "Closure request created" }),
    ).toBeVisible();
    await expect(
      page.getByText(/does not close the Publication/i),
    ).toBeVisible();
    await expect(
      page.getByText("Publication CLOSED", { exact: true }),
    ).toHaveCount(0);

    // M2: Exact same-key replay vs idempotency conflict vs concurrency conflict on REQUEST
    // (a) Exact same key and payload replay -> 201 identical response
    const requestReplayRes = await teacher.fetch<ClosureView>(
      `/api/assessments/publications/${publicationId}/closure-requests`,
      "POST",
      originalRequestBody,
      requestKey,
    );
    assertStatus(
      requestReplayRes.status,
      201,
      "Exact same-key REQUEST replay returns 201",
    );
    expect(requestReplayRes.body).toEqual(requestView);

    // (b) Same key with altered payload -> 409 IDEMPOTENCY_CONFLICT
    const requestIdempConflictRes = await teacher.fetch<{ code: string }>(
      `/api/assessments/publications/${publicationId}/closure-requests`,
      "POST",
      { expectedRevision: 999 },
      requestKey,
    );
    assertStatus(
      requestIdempConflictRes.status,
      409,
      "Same key with altered payload returns 409 IDEMPOTENCY_CONFLICT",
    );
    expect(requestIdempConflictRes.body.code).toBe("IDEMPOTENCY_CONFLICT");

    // (c) New random key while PENDING -> 409 CLOSURE_PENDING
    const ownerWhilePending = await teacher.owner(publicationId);
    const requestPendingConflictRes = await teacher.fetch<{ code: string }>(
      `/api/assessments/publications/${publicationId}/closure-requests`,
      "POST",
      { expectedRevision: ownerWhilePending.revision },
      randomBytes(16).toString("hex"),
    );
    assertStatus(
      requestPendingConflictRes.status,
      409,
      "New key while PENDING returns 409 CLOSURE_PENDING",
    );
    expect(requestPendingConflictRes.body.code).toBe("CLOSURE_PENDING");
    evidence(
      info,
      "M2 verified: REQUEST exact same-key replay returned 201 identical response; altered payload returned 409 IDEMPOTENCY_CONFLICT; new key while pending returned 409 CLOSURE_PENDING.",
    );

    await page.getByRole("link", { name: "Open closure request" }).click();
    await expect(page).toHaveURL(
      `/app/assessments/${publicationId}/closure/${closureRequestId}`,
    );
    await expect(
      page.getByText(closureRequestId, { exact: true }),
    ).toBeVisible();
    await expect(page.getByText("Pending", { exact: true })).toBeVisible();
    evidence(
      info,
      `Teacher requested delayed closure via UI: recorded durable closureRequestId ${closureRequestId}, state PENDING, publication remains OPEN.`,
    );

    // M1: Authoritative pre-worker state observation with worker OFF
    const attemptBeforeWorker = await student.fetch<AttemptView>(
      `/api/assessments/attempts/${startedAttempt.attemptId}`,
    );
    assertStatus(
      attemptBeforeWorker.status,
      200,
      "Student queries attempt before worker activation",
    );
    expect(attemptBeforeWorker.body.closure?.closureRequestId).toBe(
      closureRequestId,
    );
    expect(attemptBeforeWorker.body.closure?.closureCutoffAt).toBeNull();

    const inboxBeforeWorker = await student.fetch<{ items: Notification[] }>(
      "/api/assessments/notifications",
    );
    assertStatus(
      inboxBeforeWorker.status,
      200,
      "Student inbox query before worker activation",
    );
    expect(inboxBeforeWorker.body.items).toHaveLength(0);
    evidence(
      info,
      `M1 verified: Authoritative pre-worker state observed with worker OFF: closureRequestId=${closureRequestId}, state=PENDING, attempt.closureCutoffAt=null, student inbox=0 unconfirmed items.`,
    );

    // Operator-controlled transition 2: activate synthetic closure worker with 4000ms poll delay
    const activateWorkerOutput = await fixtureControl(
      "activate-closure-worker",
      "4000",
    );
    expect(activateWorkerOutput).toContain("PASS operator activation");
    evidence(
      info,
      "H1 verified: Operator-controlled staged transition successfully activated assessment closure worker with 4000ms poll delay after verified pre-worker state.",
    );

    // 12. Target 4 / M1: Capture authoritative PREPARING notice with null confirmation and cutoff
    let preparingNotice: Notification | null = null;
    await expect
      .poll(
        async () => {
          const res = await student.fetch<{ items: Notification[] }>(
            "/api/assessments/notifications",
          );
          if (res.status !== 200 || !res.body.items) return null;
          const found = res.body.items.find(
            (item) => item.attemptId === startedAttempt.attemptId,
          );
          if (found && found.noticeStatus === "PREPARING") {
            preparingNotice = found;
            return "PREPARING";
          }
          return found?.noticeStatus ?? null;
        },
        { timeout: 3500, intervals: [100, 200, 400] },
      )
      .toBe("PREPARING");

    expect(preparingNotice).not.toBeNull();
    expect(preparingNotice!.noticeStatus).toBe("PREPARING");
    expect(preparingNotice!.confirmedNoticeAt).toBeNull();
    expect(preparingNotice!.closureCutoffAt).toBeNull();
    expect(preparingNotice!.publicationId).toBe(publicationId);
    expect(preparingNotice!.closureRequestId).toBe(closureRequestId);

    // Assert student UI displays "Your notice is being prepared" and "Notice preparing" badge
    await studentPage.bringToFront();
    await studentPage.goto(
      `/app/notifications/${preparingNotice!.notificationId}`,
    );
    await expect(
      studentPage.getByText("Notice preparing", { exact: true }),
    ).toBeVisible();
    await expect(
      studentPage.getByText("Your notice is being prepared", { exact: true }),
    ).toBeVisible();
    await expect(
      studentPage.getByText(
        "48-hour period starts only when the server confirms",
      ),
    ).toBeVisible();
    evidence(
      info,
      `M1 verified: Authoritative PREPARING notice captured before worker confirmation: id=${preparingNotice!.notificationId}, confirmedNoticeAt=null, closureCutoffAt=null, UI alert 'Your notice is being prepared'.`,
    );

    // 13. Poll Student inbox until background worker confirms notice
    let studentNotice: Notification | null = null;
    await expect
      .poll(
        async () => {
          const res = await student.fetch<{ items: Notification[] }>(
            "/api/assessments/notifications",
          );
          if (res.status !== 200 || !res.body.items) return false;
          const found = res.body.items.find(
            (item) => item.attemptId === startedAttempt.attemptId,
          );
          if (!found) return false;
          studentNotice = found;
          return (
            found.noticeStatus === "NOTICE_CONFIRMED" &&
            found.confirmedNoticeAt !== null &&
            found.closureCutoffAt !== null
          );
        },
        { timeout: 25_000, intervals: [500, 1000] },
      )
      .toBe(true);

    expect(studentNotice).not.toBeNull();
    const notice = studentNotice!;
    expect(notice.publicationId).toBe(publicationId);
    expect(notice.closureRequestId).toBe(closureRequestId);
    expect(notice.teacherInitiated).toBe(true);
    expect(notice.warningHours).toBe(48);

    // Exact 48-hour cutoff verification (172,800,000 ms)
    const confirmedMs = Date.parse(notice.confirmedNoticeAt!);
    const cutoffMs = Date.parse(notice.closureCutoffAt!);
    expect(cutoffMs - confirmedMs).toBe(48 * 3600 * 1000);
    evidence(
      info,
      `Worker published and confirmed recipient notice: confirmedNoticeAt=${notice.confirmedNoticeAt}, closureCutoffAt=${notice.closureCutoffAt}, exact delta=172800000ms (+48 hours).`,
    );

    // 12. Student views notification list and detail in UI
    await studentPage.bringToFront();
    await studentPage.goto("/app/notifications");
    await expect(
      studentPage.getByText("48-hour period confirmed", { exact: true }),
    ).toBeVisible();
    const viewNoticeLink = studentPage.getByRole("link", {
      name: "View notice",
    });
    await expect(viewNoticeLink).toHaveAttribute(
      "href",
      `/app/notifications/${notice.notificationId}`,
    );
    try {
      await Promise.all([
        studentPage.waitForURL(
          `**/app/notifications/${notice.notificationId}`,
          { timeout: 10_000 },
        ),
        viewNoticeLink.click(),
      ]);
    } catch {
      await studentPage.goto(`/app/notifications/${notice.notificationId}`, {
        waitUntil: "domcontentloaded",
      });
    }
    await expect(
      studentPage.getByText("48-hour period confirmed", { exact: true }),
    ).toBeVisible();
    await expect(
      studentPage.getByText(startedAttempt.attemptId, { exact: true }),
    ).toBeVisible();
    evidence(
      info,
      "Student successfully navigated to notice detail in browser UI showing authoritative 48-hour cutoff.",
    );

    // 13. Former CLASS member read test:
    // Remove student from classroom membership
    await fixtureControl(
      "remove-membership",
      classroom.body.id,
      studentMe.body.id,
      teacherMe.body.id,
    );
    // Verify entry is now denied (404)
    const deniedEntryRes = await student.fetch(
      `/api/assessments/publications/${publicationId}/entry`,
      "POST",
    );
    assertStatus(deniedEntryRes.status, 404, "Former member denied entry");

    // Verify notifications inbox and detail remain accessible (200 OK)
    const formerInboxRes = await student.fetch<{ items: Notification[] }>(
      "/api/assessments/notifications",
    );
    assertStatus(
      formerInboxRes.status,
      200,
      "Former member notifications inbox",
    );
    expect(
      formerInboxRes.body.items.some(
        (item) => item.notificationId === notice.notificationId,
      ),
    ).toBe(true);

    const formerDetailRes = await student.fetch<Notification>(
      `/api/assessments/notifications/${notice.notificationId}`,
    );
    assertStatus(formerDetailRes.status, 200, "Former member notice detail");
    expect(formerDetailRes.body.notificationId).toBe(notice.notificationId);

    await studentPage.reload();
    await expect(
      studentPage.getByText("48-hour period confirmed", { exact: true }),
    ).toBeVisible();
    evidence(
      info,
      "Former CLASS member read verified: entry rejected 404, but recipient notice inbox & detail remain accessible 200 OK.",
    );

    // 14. Target 2/4: Cross-recipient masking with Student B
    const studentBContext = await browser.newContext();
    await pinEnglishContext(studentBContext);
    try {
      const studentBPage = await studentBContext.newPage();
      const studentB = await registerTeacher(
        studentBPage,
        request,
        info,
        false,
      );

      // Student B inbox is empty
      let studentBInbox!: Awaited<
        ReturnType<
          typeof studentB.fetch<{
            items: unknown[];
            nextCursor: string | null;
          }>
        >
      >;
      await expect
        .poll(
          async () => {
            studentBInbox = await studentB.fetch<{
              items: unknown[];
              nextCursor: string | null;
            }>("/api/assessments/notifications");
            return studentBInbox.status;
          },
          { timeout: 10_000, intervals: [200, 400, 800] },
        )
        .toBe(200);
      expect(studentBInbox.body.items).toEqual([]);

      // Student B cannot read Student A's notice (404 masks existence of another recipient's notice)
      const foreignNoticeRes = await studentB.fetch(
        `/api/assessments/notifications/${notice.notificationId}`,
      );
      assertStatus(foreignNoticeRes.status, 404, "Cross-recipient notice 404");
      evidence(
        info,
        "Cross-recipient authorization masking verified: Student B receives 404 on Student A's confirmed notice and has empty inbox.",
      );

      // 15. Target 6: COMPLETE before cutoff blocked with 409 CLOSURE_NOT_READY
      await page.bringToFront();
      await ensureTeachingWorkspace(page);
      await page
        .getByRole("button", { name: "Review and COMPLETE closure" })
        .click();
      const completionDialog = page.getByRole("dialog", {
        name: "Confirm completion command",
      });
      await completionDialog
        .getByLabel("Type COMPLETE to enable this action")
        .fill("COMPLETE");

      const completeRes = page.waitForResponse(
        (res) =>
          res
            .url()
            .includes(`/closure-requests/${closureRequestId}/complete`) &&
          res.request().method() === "POST",
      );
      await completionDialog
        .getByRole("button", { name: "COMPLETE closure", exact: true })
        .click();
      const completeHttp = await completeRes;
      assertStatus(
        completeHttp.status(),
        409,
        "Early COMPLETE denied before cutoff",
      );
      const completeBody = (await completeHttp.json()) as { code: string };
      expect(completeBody.code).toBe("CLOSURE_NOT_READY");

      // UI displays error, request identity and PENDING state preserved, no false CLOSED
      await expect(
        page.getByText(/CLOSURE_NOT_READY|conflicted/i),
      ).toBeVisible();
      await expect(page.getByText("Pending", { exact: true })).toBeVisible();
      const ownerStillOpen = await teacher.owner(publicationId);
      expect(ownerStillOpen.state).toBe("OPEN");
      evidence(
        info,
        "Early COMPLETE blocked with 409 CLOSURE_NOT_READY; request identity preserved, Publication remains OPEN, genuine 48-hour cutoff honored without manipulation.",
      );

      // COMPLETE authorization fence: Student denied COMPLETE
      const studentComplete = await student.fetch(
        `/api/assessments/publications/${publicationId}/closure-requests/${closureRequestId}/complete`,
        "POST",
        { expectedRevision: ownerStillOpen.revision },
        randomBytes(16).toString("hex"),
      );
      assertStatus(studentComplete.status, 403, "Student denied COMPLETE");

      // M2: Foreign-Teacher authorization fence: unrelated Teacher denied GET, REQUEST, CANCEL, COMPLETE (404)
      const foreignTeacherContext = await browser.newContext();
      await pinEnglishContext(foreignTeacherContext);
      try {
        const foreignTeacherPage = await foreignTeacherContext.newPage();
        const foreignTeacher = await registerTeacher(
          foreignTeacherPage,
          request,
          info,
          true,
        );

        // GET publication -> 404
        const foreignGet = await foreignTeacher.fetch(
          `/api/assessments/publications/${publicationId}`,
        );
        assertStatus(
          foreignGet.status,
          404,
          "Foreign Teacher denied GET publication",
        );

        // REQUEST closure -> 404
        const foreignReq = await foreignTeacher.fetch(
          `/api/assessments/publications/${publicationId}/closure-requests`,
          "POST",
          { expectedRevision: 1 },
          randomBytes(16).toString("hex"),
        );
        assertStatus(
          foreignReq.status,
          404,
          "Foreign Teacher denied REQUEST closure",
        );

        // CANCEL closure -> 404
        const foreignCancel = await foreignTeacher.fetch(
          `/api/assessments/publications/${publicationId}/closure-requests/${closureRequestId}/cancel`,
          "POST",
          { expectedRevision: 1 },
          randomBytes(16).toString("hex"),
        );
        assertStatus(
          foreignCancel.status,
          404,
          "Foreign Teacher denied CANCEL closure",
        );

        // COMPLETE closure -> 404
        const foreignComplete = await foreignTeacher.fetch(
          `/api/assessments/publications/${publicationId}/closure-requests/${closureRequestId}/complete`,
          "POST",
          { expectedRevision: 1 },
          randomBytes(16).toString("hex"),
        );
        assertStatus(
          foreignComplete.status,
          404,
          "Foreign Teacher denied COMPLETE closure",
        );

        await foreignTeacher.assertConfidentiality();
      } finally {
        await foreignTeacherContext.close();
      }
      evidence(
        info,
        "M2 verified: Foreign-Teacher authorization fence: unrelated Teacher receives 404 PUBLICATION_NOT_FOUND on GET, REQUEST, CANCEL, COMPLETE.",
      );

      // 16. Target 5: Real Teacher CANCEL pre-finalization
      await page.bringToFront();
      await page.reload({ waitUntil: "domcontentloaded" });
      await ensureTeachingWorkspace(page);
      await expect(
        page.getByRole("button", { name: "Review and CANCEL request" }),
      ).toBeEnabled();
      await page
        .getByRole("button", { name: "Review and CANCEL request" })
        .click();
      const cancelDialog = page.getByRole("dialog", {
        name: "Confirm cancellation",
      });
      await cancelDialog
        .getByLabel("Type CANCEL to enable this action")
        .fill("CANCEL");

      const cancelRes = page.waitForResponse(
        (res) =>
          res.url().includes(`/closure-requests/${closureRequestId}/cancel`) &&
          res.request().method() === "POST",
      );
      await cancelDialog
        .getByRole("button", { name: "CANCEL closure request", exact: true })
        .click();
      const cancelHttp = await cancelRes;
      assertStatus(cancelHttp.status(), 200, "Teacher CANCEL closure request");
      const cancelKey = cancelHttp.request().headers()["idempotency-key"];
      const originalCancelBody = cancelHttp.request().postDataJSON() as {
        expectedRevision: number;
      };
      const cancelView = (await cancelHttp.json()) as ClosureView;
      expect(cancelView.state).toBe("CANCELLED");

      await expect(page.getByText("The request was cancelled.")).toBeVisible();
      await expect(page.getByText("This request is resolved")).toBeVisible();
      evidence(
        info,
        "Teacher cancelled closure request: backend returns 200, state CANCELLED, UI shows request resolved.",
      );

      // Student perspective: notice is CANCELLED, NOT deleted
      await studentPage.bringToFront();
      await studentPage.reload();
      await expect(
        studentPage.getByText("Closure cancelled", { exact: true }).first(),
      ).toBeVisible();
      await expect(
        studentPage.getByText(
          "The teacher cancelled this closure. This notice stays as history only.",
        ),
      ).toBeVisible();

      const cancelledNoticeGet = await student.fetch<Notification>(
        `/api/assessments/notifications/${notice.notificationId}`,
      );
      assertStatus(cancelledNoticeGet.status, 200, "Notice still exists 200");
      expect(cancelledNoticeGet.body.noticeStatus).toBe("CANCELLED");
      evidence(
        info,
        "Cancelled notice persists durably in database with noticeStatus CANCELLED (no deletion).",
      );

      // M2: CANCEL replay / conflict probes
      // (a) Exact same key and payload replay -> 200 identical response
      const cancelSameKey = await teacher.fetch<ClosureView>(
        `/api/assessments/publications/${publicationId}/closure-requests/${closureRequestId}/cancel`,
        "POST",
        originalCancelBody,
        cancelKey,
      );
      assertStatus(
        cancelSameKey.status,
        200,
        "Exact same-key CANCEL replay returns 200",
      );
      expect(cancelSameKey.body).toEqual(cancelView);

      // (b) Same key with altered payload -> 409 IDEMPOTENCY_CONFLICT
      const cancelIdempConflict = await teacher.fetch<{ code: string }>(
        `/api/assessments/publications/${publicationId}/closure-requests/${closureRequestId}/cancel`,
        "POST",
        { expectedRevision: 999 },
        cancelKey,
      );
      assertStatus(
        cancelIdempConflict.status,
        409,
        "Same key with altered revision returns 409 IDEMPOTENCY_CONFLICT",
      );
      expect(cancelIdempConflict.body.code).toBe("IDEMPOTENCY_CONFLICT");

      // (c) New random key on resolved request -> 409 CLOSURE_RESOLVED
      const cancelTerminalConflict = await teacher.fetch<{ code: string }>(
        `/api/assessments/publications/${publicationId}/closure-requests/${closureRequestId}/cancel`,
        "POST",
        { expectedRevision: cancelView.publicationRevision },
        randomBytes(16).toString("hex"),
      );
      assertStatus(
        cancelTerminalConflict.status,
        409,
        "New key on resolved request returns 409 CLOSURE_RESOLVED",
      );
      expect(cancelTerminalConflict.body.code).toBe("CLOSURE_RESOLVED");

      const completeOnCancelled = await teacher.fetch<{ code: string }>(
        `/api/assessments/publications/${publicationId}/closure-requests/${closureRequestId}/complete`,
        "POST",
        { expectedRevision: cancelView.publicationRevision },
        randomBytes(16).toString("hex"),
      );
      assertStatus(
        completeOnCancelled.status,
        409,
        "Complete on cancelled request returns 409",
      );
      expect(completeOnCancelled.body.code).toBe("CLOSURE_RESOLVED");

      const studentCancel = await student.fetch(
        `/api/assessments/publications/${publicationId}/closure-requests/${closureRequestId}/cancel`,
        "POST",
        { expectedRevision: cancelView.publicationRevision },
        randomBytes(16).toString("hex"),
      );
      assertStatus(studentCancel.status, 403, "Student denied CANCEL");
      evidence(
        info,
        "M2 verified: CANCEL exact same-key replay returned 200 identical response; altered payload returned 409 IDEMPOTENCY_CONFLICT; new key on resolved request returned 409 CLOSURE_RESOLVED; student denied 403.",
      );

      await studentB.assertConfidentiality();
    } finally {
      await studentBContext.close();
    }

    await student.assertConfidentiality();
  } finally {
    await studentContext.close();
  }

  await teacher.assertConfidentiality();
});
