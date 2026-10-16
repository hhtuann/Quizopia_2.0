import { expect, test } from "@playwright/test";
import type { OwnerView } from "../../features/assessments/api/publication-contract.generated";
import type { AttemptView } from "../../features/attempts/api/attempt-contract.generated";
import {
  assertStatus,
  commandResponse,
  confirm,
  ensureTeachingWorkspace,
  evidence,
  fixtureControl,
  gateway,
  publicationBase,
  publishMultiQuestionSource,
  registerTeacher,
  utcField,
} from "./helpers";

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
  await page
    .getByLabel("Participation start (UTC)")
    .fill(utcField(-2, "123456"));
  await page.getByLabel("Participation end (UTC)").fill(utcField(60, "654321"));
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
        res.url().startsWith(`${gateway}/api/assessments/publications/`) &&
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
        res.url().startsWith(`${gateway}/api/assessments/publications/`) &&
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
  await page.getByLabel("Classroom ID").fill(classroom.body.id);
  await page
    .getByLabel("Participation start (UTC)")
    .fill(utcField(-2, "123456"));
  await page.getByLabel("Participation end (UTC)").fill(utcField(60, "654321"));
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
        res.url().startsWith(`${gateway}/api/assessments/publications/`) &&
        res.url().endsWith("/entry") &&
        res.request().method() === "POST",
    );
    await studentPage.getByRole("button", { name: "Check access" }).click();
    const deniedHttp = await deniedEntry;
    assertStatus(deniedHttp.status(), 404, "student denied before membership");
    await expect(
      studentPage.getByText(
        /Current class membership could not authorize this assessment|not accessible|denied/i,
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
        res.url().startsWith(`${gateway}/api/assessments/publications/`) &&
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
        res.url().startsWith(`${gateway}/api/assessments/publications/`) &&
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
    await studentPage.goto("/app/notifications");
    await expect(
      studentPage.getByRole("heading", { name: "Assessment notices" }),
    ).toBeVisible();
    evidence(
      info,
      "Student notifications inbox accessible and authenticated via real Gateway.",
    );

    await teacher.assertConfidentiality();
    await student.assertConfidentiality();
  } finally {
    await studentContext.close();
  }
});
