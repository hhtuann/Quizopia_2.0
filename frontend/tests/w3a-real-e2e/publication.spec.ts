import { createHash, randomBytes, randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import type {
  Configuration,
  OwnerView,
} from "../../features/assessments/api/publication-contract.generated";
import {
  assertStatus,
  commandResponse,
  confirm,
  evidence,
  enableTeacherUi,
  fixtureControl,
  gateway,
  matchGatewayUrl,
  publicationBase,
  publishExactSource,
  registerTeacher,
  utcField,
  utcLocalParts,
} from "./helpers";

test("real PUBLIC pinned create/replay/edit/conflicts/503/OPEN/extend/CLOSE and confidential session", async ({
  page,
  request,
}, info) => {
  const actor = await registerTeacher(page, request, info);
  const source = await publishExactSource(page);
  evidence(
    info,
    "Actual Quiz draft/save/publish/history selected immutable version one through frontend.",
  );
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
  const originalConfiguration = createdHttp
    .request()
    .postDataJSON() as Configuration;
  const key = createdHttp.request().headers()["idempotency-key"];
  expect(/^[0-9a-f]{64}$/.test(key)).toBe(true);
  await expect(page).toHaveURL(`/app/assessments/${created.publicationId}`);
  const current = await actor.owner(created.publicationId);
  expect(current.state).toBe("DRAFT");
  expect(current.configuration.quizVersionId === source.quizVersionId).toBe(
    true,
  );
  expect(new Date(current.configuration.startAt).getTime()).toBe(
    Date.parse(scheduleParts.instant),
  );
  expect(new Date(current.configuration.endAt).getTime()).toBe(
    Date.parse(endParts.instant),
  );
  expect(Boolean(current.privateLocator)).toBe(true);
  expect(await page.locator("code").count()).toBe(0);
  evidence(
    info,
    "PUBLIC POST 201, fresh no-store owner GET 200; exact pinned IDs and microseconds retained; locator initially hidden.",
  );

  const replay = await actor.fetch<OwnerView>(
    publicationBase,
    "POST",
    originalConfiguration,
    key,
  );
  assertStatus(replay.status, 201, "real idempotent creation replay");
  expect(replay.body.publicationId === created.publicationId).toBe(true);
  const conflict = await actor.fetch<{ code: string }>(
    publicationBase,
    "POST",
    { ...originalConfiguration, title: "Different request under same key" },
    key,
  );
  assertStatus(conflict.status, 409, "real create key payload conflict");
  expect(conflict.body.code).toBe("IDEMPOTENCY_CONFLICT");
  const missingSource = await actor.fetch<{ code: string }>(
    publicationBase,
    "POST",
    {
      ...originalConfiguration,
      quizId: randomUUID(),
      quizVersionId: randomUUID(),
    },
    randomBytes(32).toString("hex"),
  );
  assertStatus(missingSource.status, 404, "inaccessible immutable source");
  expect(missingSource.body.code).toBe("SOURCE_NOT_ACCESSIBLE");
  const absentOwner = await actor.fetch<{ code: string }>(
    `${publicationBase}/${randomUUID()}`,
  );
  assertStatus(absentOwner.status, 404, "absent owner resource");
  const unauthenticated = await page.evaluate(
    async (url) =>
      (
        await fetch(url, {
          headers: { Authorization: "Bearer invalid-real-acceptance-token" },
        })
      ).status,
    `${gateway}${publicationBase}/${created.publicationId}`,
  );
  assertStatus(unauthenticated, 401, "real Gateway invalid USER token");
  evidence(
    info,
    "Actual creation replay 201 same ID; payload/key conflict 409 IDEMPOTENCY_CONFLICT; source/owner 404 and invalid-token 401.",
  );

  await page.getByRole("button", { name: "Edit DRAFT" }).click();
  await page.getByLabel("Publication title").fill("Local stale edit preserved");
  const externalUpdate = await actor.fetch<OwnerView>(
    `${publicationBase}/${created.publicationId}`,
    "PUT",
    {
      expectedRevision: current.revision,
      configuration: {
        ...current.configuration,
        title: "Authoritative concurrent update",
      },
    },
  );
  assertStatus(externalUpdate.status, 200, "actual concurrent owner update");
  await page.getByRole("button", { name: "Review DRAFT changes" }).click();
  const staleResponse = commandResponse(
    page,
    `/${created.publicationId}`,
    "PUT",
  );
  await confirm(page, "Save DRAFT");
  assertStatus(
    (await staleResponse).status(),
    409,
    "stale UI expectedRevision",
  );
  await expect(page.getByLabel("Publication title")).toHaveValue(
    "Local stale edit preserved",
  );
  await page
    .getByRole("button", {
      name: "Use server configuration (discard local edits)",
    })
    .click();
  await page
    .getByLabel("Publication title")
    .fill("Real PUBLIC reviewed assessment");
  await page.getByRole("button", { name: "Review DRAFT changes" }).click();
  const savedResponse = commandResponse(
    page,
    `/${created.publicationId}`,
    "PUT",
  );
  await confirm(page, "Save DRAFT");
  assertStatus(
    (await savedResponse).status(),
    200,
    "real reviewed DRAFT update",
  );
  await expect(
    page.getByRole("heading", { name: "Real PUBLIC reviewed assessment" }),
  ).toBeVisible();
  evidence(
    info,
    "Concurrent real PUT 200; stale UI PUT 409 preserves local values; explicit discard then reviewed PUT 200.",
  );

  const refreshBefore = actor.authRequests.filter((path) =>
    path.endsWith("/refresh"),
  ).length;
  const logoutBefore = actor.authRequests.filter((path) =>
    path.endsWith("/logout"),
  ).length;
  await fixtureControl("pause-quiz");
  try {
    await page
      .getByRole("button", { name: "Review and OPEN", exact: true })
      .click();
    const unavailableResponse = commandResponse(page, "/open", "POST");
    await confirm(page, "OPEN Publication");
    const unavailable = await unavailableResponse;
    assertStatus(
      unavailable.status(),
      503,
      "isolated unavailable required Quiz authority",
    );
    expect(((await unavailable.json()) as { code: string }).code).toBe(
      "DEPENDENCY_UNAVAILABLE",
    );
    await expect(
      page.getByRole("heading", { name: "Reconcile before continuing" }),
    ).toBeVisible();
    expect(
      actor.authRequests.filter((path) => path.endsWith("/refresh")).length,
    ).toBe(refreshBefore);
    expect(
      actor.authRequests.filter((path) => path.endsWith("/logout")).length,
    ).toBe(logoutBefore);
    expect((await actor.owner(created.publicationId)).state).toBe("DRAFT");
  } finally {
    await fixtureControl("resume-quiz");
  }
  await page
    .getByRole("button", { name: "Reconcile state", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Retry original command" }),
  ).toBeEnabled();
  const openedResponse = commandResponse(page, "/open", "POST");
  await page.getByRole("button", { name: "Retry original command" }).click();
  assertStatus(
    (await openedResponse).status(),
    200,
    "manual original OPEN after dependency recovery",
  );
  const opened = await actor.owner(created.publicationId);
  expect(opened.state).toBe("OPEN");
  expect(opened.configuration.quizVersionId === source.quizVersionId).toBe(
    true,
  );
  evidence(
    info,
    "Owned Quiz SIGSTOP yields real 503 DEPENDENCY_UNAVAILABLE; no logout/refresh; actual DRAFT retained; resume and manual reconciliation/original OPEN 200.",
  );

  await page
    .getByRole("button", { name: "Extend participation schedule" })
    .click();
  const extendedParts = utcLocalParts(120);
  await page
    .getByLabel("Extended participation end", { exact: true })
    .fill(extendedParts.date);
  await page
    .getByLabel("Extended participation end time")
    .fill(extendedParts.time);
  await page.getByLabel("Extended Attempt duration (minutes)").fill("65");
  await page.getByRole("button", { name: "Review schedule extension" }).click();
  const extendedResponse = commandResponse(page, "/schedule", "PATCH");
  await confirm(page, "Save schedule extension");
  assertStatus((await extendedResponse).status(), 200, "real OPEN extension");
  const extended = await actor.owner(created.publicationId);
  expect(extended.configuration.startAt === opened.configuration.startAt).toBe(
    true,
  );
  expect(extended.configuration.durationMinutes).toBe(65);

  const attempt = await actor.fetch<{
    attemptId: string;
    state: string;
    revision: string;
  }>(
    `${publicationBase}/${created.publicationId}/attempts/start`,
    "POST",
    { locator: extended.privateLocator },
    randomBytes(32).toString("hex"),
  );
  assertStatus(
    attempt.status,
    201,
    "real PUBLIC ACTIVE Attempt under current STUDENT role",
  );
  expect(attempt.body.state).toBe("ACTIVE");
  await page.getByRole("button", { name: "Review and CLOSE" }).click();
  const blockedClose = commandResponse(page, "/close", "POST");
  await confirm(page, "CLOSE Publication");
  assertStatus(
    (await blockedClose).status(),
    409,
    "real ACTIVE Attempt prevents CLOSE",
  );
  await expect(page.getByText(/Active Attempts prevent closure/)).toBeVisible();
  expect((await actor.owner(created.publicationId)).state).toBe("OPEN");
  const abandon = await actor.fetch(
    `/api/assessments/attempts/${attempt.body.attemptId}/abandon`,
    "POST",
    { expectedRevision: attempt.body.revision },
    randomBytes(32).toString("hex"),
  );
  assertStatus(abandon.status, 200, "actual Student abandons own Attempt");
  await page.getByRole("button", { name: "Review and CLOSE" }).click();
  const closedResponse = commandResponse(page, "/close", "POST");
  await confirm(page, "CLOSE Publication");
  assertStatus((await closedResponse).status(), 200, "eligible direct CLOSE");
  await expect(
    page.getByText("Publication CLOSED", { exact: true }),
  ).toBeVisible();
  expect((await actor.owner(created.publicationId)).state).toBe("CLOSED");
  await actor.assertConfidentiality();
  evidence(
    info,
    "Actual PATCH extension 200; PUBLIC ACTIVE start 201 blocks CLOSE 409, remains OPEN; own abandon 200 permits direct CLOSE 200 and fresh durable CLOSED GET 200.",
  );
  evidence(
    info,
    "HttpOnly SameSite=Lax refresh cookie, USER-only browser bearer, memory-only token; private locator absent URLs/links/console/storage; trace/video/screenshots disabled.",
  );
});

test("real CLASS ownership/membership, explicit monitoring, STUDENT denial and ACTIVE CLOSE conflict", async ({
  browser,
  page,
  request,
}, info) => {
  const teacher = await registerTeacher(page, request, info);
  const teacherMe = await teacher.fetch<{ id: string }>("/api/auth/me");
  const readinessId = randomUUID();
  await expect
    .poll(
      async () => {
        const ready = await teacher.fetch<{ code?: string }>(
          `${publicationBase}/${readinessId}`,
        );
        if (ready.status !== 503) {
          assertStatus(
            ready.status,
            404,
            "protected Teacher admission fixture readiness",
          );
          expect(ready.body.code).toBe("PUBLICATION_NOT_FOUND");
        }
        return ready.status;
      },
      { timeout: 10_000, intervals: [200, 400, 800] },
    )
    .toBe(404);
  const classroom = await teacher.fetch<{ id: string }>("/classrooms", "POST", {
    name: "Disposable W3-A Classroom",
    subject: "Integration",
    grade: "10",
    description: "Owned isolated acceptance resource",
  });
  assertStatus(classroom.status, 201, "real Teacher-owned Classroom creation");
  const source = await publishExactSource(page);
  await page.getByRole("radio", { name: "Classroom", exact: true }).check();
  await page
    .getByRole("radio", { name: "Disposable W3-A Classroom", exact: true })
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
  await expect(page).toHaveURL(`/app/assessments/${created.publicationId}`);
  await expect(
    page.getByRole("button", { name: "Review and OPEN" }),
  ).toBeDisabled();
  expect(created.privateLocator).toBeNull();
  const wrongClass = await teacher.fetch<{ code: string }>(
    publicationBase,
    "POST",
    { ...created.configuration, classroomId: randomUUID() },
    randomBytes(32).toString("hex"),
  );
  assertStatus(
    wrongClass.status,
    404,
    "unauthorized/unavailable Classroom reference",
  );
  expect(wrongClass.body.code).toBe("CLASSROOM_NOT_ACCESSIBLE");
  await page.getByRole("button", { name: "Edit DRAFT" }).click();
  await page.getByRole("radio", { name: "Disabled — no monitoring" }).check();
  await page.getByRole("button", { name: "Review DRAFT changes" }).click();
  const updateResponse = commandResponse(
    page,
    `/${created.publicationId}`,
    "PUT",
  );
  await confirm(page, "Save DRAFT");
  assertStatus(
    (await updateResponse).status(),
    200,
    "explicit CLASS monitoring choice",
  );
  await page.getByRole("button", { name: "Review and OPEN" }).click();
  const openedResponse = commandResponse(page, "/open", "POST");
  await confirm(page, "OPEN Publication");
  assertStatus(
    (await openedResponse).status(),
    200,
    "authoritative CLASS OPEN",
  );
  const opened = await teacher.owner(created.publicationId);
  expect(opened.configuration.classroomId === classroom.body.id).toBe(true);
  expect(opened.configuration.quizVersionId === source.quizVersionId).toBe(
    true,
  );
  expect(opened.configuration.monitoringChoice).toBe("DISABLED");
  evidence(
    info,
    "CLASS actual Classroom POST 201 and Publication create 201; unknown Classroom denied 404; UNCONFIGURED prevents UI OPEN; explicit DISABLED PUT 200 then owned CLASS OPEN 200.",
  );

  const studentContext = await browser.newContext();
  try {
    const studentPage = await studentContext.newPage();
    const student = await registerTeacher(studentPage, request, info, false);
    const studentMe = await student.fetch<{ id: string }>("/api/auth/me");
    const deniedOwner = await student.fetch(
      `${publicationBase}/${created.publicationId}`,
    );
    assertStatus(
      deniedOwner.status,
      403,
      "current Student cannot request Teacher owner projection",
    );
    await studentPage.goto(`/app/assessments/${created.publicationId}`);
    await expect(
      studentPage.getByText("Teacher access required", { exact: true }),
    ).toBeVisible();
    await expect(
      studentPage.getByRole("button", { name: "Review and CLOSE" }),
    ).toHaveCount(0);
    const notMember = await student.fetch(
      `${publicationBase}/${created.publicationId}/attempts/start`,
      "POST",
      {},
      randomBytes(32).toString("hex"),
    );
    assertStatus(
      notMember.status,
      404,
      "CLASS start denied before membership fixture",
    );
    await fixtureControl(
      "seed-membership",
      classroom.body.id,
      studentMe.body.id,
      teacherMe.body.id,
    );
    const attempt = await student.fetch<{
      attemptId: string;
      state: string;
      revision: string;
    }>(
      `${publicationBase}/${created.publicationId}/attempts/start`,
      "POST",
      {},
      randomBytes(32).toString("hex"),
    );
    assertStatus(
      attempt.status,
      201,
      "real authorized CLASS ACTIVE start after isolated membership fixture",
    );
    expect(attempt.body.state).toBe("ACTIVE");
    await page.getByRole("button", { name: "Review and CLOSE" }).click();
    const blockedClose = commandResponse(page, "/close", "POST");
    await confirm(page, "CLOSE Publication");
    const blocked = await blockedClose;
    assertStatus(
      blocked.status(),
      409,
      "real CLASS ACTIVE Attempt prevents closure",
    );
    expect(((await blocked.json()) as { code: string }).code).toBe(
      "ACTIVE_ATTEMPTS",
    );
    await expect(
      page.getByText(/Active Attempts prevent closure/),
    ).toBeVisible();
    expect((await teacher.owner(created.publicationId)).state).toBe("OPEN");
    const abandoned = await student.fetch(
      `/api/assessments/attempts/${attempt.body.attemptId}/abandon`,
      "POST",
      { expectedRevision: attempt.body.revision },
      randomBytes(32).toString("hex"),
    );
    assertStatus(abandoned.status, 200, "real CLASS Student terminalization");
    await studentPage.goto("/app");
    await enableTeacherUi(studentPage, student);
    const otherClassroom = await student.fetch<{ id: string }>(
      "/classrooms",
      "POST",
      {
        name: "Another disposable Teacher Classroom",
        subject: "Integration",
        grade: "10",
      },
    );
    assertStatus(
      otherClassroom.status,
      201,
      "different Teacher actual Classroom",
    );
    const foreignClass = await teacher.fetch<{ code: string }>(
      publicationBase,
      "POST",
      {
        ...created.configuration,
        classroomId: otherClassroom.body.id,
      },
      randomBytes(32).toString("hex"),
    );
    assertStatus(
      foreignClass.status,
      404,
      "existing foreign Teacher Classroom denied",
    );
    expect(foreignClass.body.code).toBe("CLASSROOM_NOT_ACCESSIBLE");
    const foreignOwner = await student.fetch<{ code: string }>(
      `${publicationBase}/${created.publicationId}`,
    );
    assertStatus(
      foreignOwner.status,
      404,
      "other current Teacher denied real owner's Publication",
    );
    expect(foreignOwner.body.code).toBe("PUBLICATION_NOT_FOUND");
    evidence(
      info,
      "Existing different Teacher Classroom reference 404 CLASSROOM_NOT_ACCESSIBLE; different current Teacher owner GET 404 PUBLICATION_NOT_FOUND.",
    );

    const documentSentinel = await page.evaluate(() => {
      const sentinel = crypto.randomUUID();
      Object.defineProperty(window, "__w3aDocumentSentinel", {
        value: sentinel,
        configurable: true,
      });
      return sentinel;
    });
    await fixtureControl("pause-assessment");
    try {
      const pendingOwner = page.waitForRequest(
        (request) =>
          matchGatewayUrl(
            request.url(),
            `${publicationBase}/${created.publicationId}`,
            true,
          ) && request.method() === "GET",
      );
      await page.getByRole("button", { name: "Refresh status" }).click();
      const oldRequest = await pendingOwner;
      await page.getByRole("button", { name: /Open user menu for/ }).click();
      const loggedOut = page.waitForResponse((response) =>
        matchGatewayUrl(response.url(), "/api/auth/logout", true),
      );
      await page.getByRole("menuitem", { name: "Sign out" }).click();
      assertStatus(
        (await loggedOut).status(),
        204,
        "logout while old owner response is stalled",
      );
      await expect(
        page.getByRole("heading", { name: "Sign in to continue" }),
      ).toBeVisible();
      expect(
        (await page.evaluate(
          () =>
            (window as unknown as { __w3aDocumentSentinel?: string })
              .__w3aDocumentSentinel,
        )) === documentSentinel,
      ).toBe(true);
      const switched = await student.signInOn(page);
      expect(
        (await page.evaluate(
          () =>
            (window as unknown as { __w3aDocumentSentinel?: string })
              .__w3aDocumentSentinel,
        )) === documentSentinel,
      ).toBe(true);
      await fixtureControl("resume-assessment");
      await expect(
        page.getByRole("heading", { name: "Your Quizopia workspace" }),
      ).toBeVisible();
      expect(
        await page.getByText("Publication ID:", { exact: false }).count(),
      ).toBe(0);
      await page.goto(`/app/assessments/${created.publicationId}`);
      const workspaceSwitch = page.getByRole("button", {
        name: "Switch to Teaching workspace",
        exact: true,
      });
      await workspaceSwitch.click();
      await expect(
        page.getByText("Publication could not be loaded", { exact: true }),
      ).toBeVisible();
      expect(
        await page
          .getByRole("heading", {
            name: created.configuration.title,
            exact: true,
          })
          .count(),
      ).toBe(0);
      expect(
        (await switched.fetch(`${publicationBase}/${created.publicationId}`))
          .status,
      ).toBe(404);
      expect(
        (await oldRequest.response()) === null || oldRequest.failure() !== null,
      ).toBe(true);
      evidence(
        info,
        "Same browser: owned Assessment pause stalls old owner GET; logout 204 and other-account login succeed; resume leaves old request canceled and foreign owner UI/data denied 404.",
      );
      await switched.assertConfidentiality();
    } finally {
      await fixtureControl("resume-assessment");
    }
    await teacher.assertConfidentiality();
    await student.assertConfidentiality();
    evidence(
      info,
      "Separate Student owner GET 403 and Teacher UI blocked; CLASS membership denial 404; controlled owned-Classroom membership fixture then actual ACTIVE start 201, CLOSE 409 ACTIVE_ATTEMPTS and fresh OPEN GET 200; own abandon 200.",
    );
  } finally {
    await studentContext.close();
  }
});

test("real committed creation with lost response retains original key and reconciles once", async ({
  page,
  request,
}, info) => {
  const actor = await registerTeacher(page, request, info);
  const actorMe = await actor.fetch<{ id: string }>("/api/auth/me");
  const source = await publishExactSource(page);
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
  const creationRequests: { key: string; body: string | null }[] = [];
  let receivedCreationResponse = false;
  page.on("request", (outgoing) => {
    if (
      matchGatewayUrl(outgoing.url(), publicationBase, true) &&
      outgoing.method() === "POST"
    )
      creationRequests.push({
        key: outgoing.headers()["idempotency-key"],
        body: outgoing.postData(),
      });
  });
  page.on("response", (response) => {
    if (
      matchGatewayUrl(response.url(), publicationBase, true) &&
      response.request().method() === "POST"
    )
      receivedCreationResponse = true;
  });
  const refreshBefore = actor.authRequests.filter((path) =>
    path.endsWith("/refresh"),
  ).length;
  const logoutBefore = actor.authRequests.filter((path) =>
    path.endsWith("/logout"),
  ).length;
  let keyDigest = "";
  await fixtureControl("pause-quiz");
  try {
    const outgoing = page.waitForRequest(
      (pending) =>
        matchGatewayUrl(pending.url(), publicationBase, true) &&
        pending.method() === "POST",
    );
    await confirm(page, "Create DRAFT");
    const original = await outgoing;
    keyDigest = createHash("sha256")
      .update(original.headers()["idempotency-key"])
      .digest("hex");
    await expect
      .poll(async () => fixtureControl("quiz-pending-request"), {
        timeout: 5_000,
        intervals: [50, 100, 200],
      })
      .toBe("1");
    await fixtureControl("pause-gateway");
    await fixtureControl("resume-quiz");
    await expect
      .poll(
        async () =>
          fixtureControl("creation-receipt-count", actorMe.body.id, keyDigest),
        { timeout: 5_000, intervals: [100, 200, 400] },
      )
      .toBe("1");
    expect(receivedCreationResponse).toBe(false);
    evidence(
      info,
      "Owned Gateway paused after real create dispatch; Quiz resumed; owned read-only receipt observer proves one committed creation before browser receives any response.",
    );
    await expect(
      page.getByText("Creation outcome is uncertain", { exact: true }),
    ).toBeVisible({ timeout: 20_000 });
    expect(creationRequests.length).toBe(1);
    expect(
      actor.authRequests.filter((path) => path.endsWith("/refresh")).length,
    ).toBe(refreshBefore);
    expect(
      actor.authRequests.filter((path) => path.endsWith("/logout")).length,
    ).toBe(logoutBefore);
    await expect(page.getByLabel("Publication title")).toBeDisabled();
  } finally {
    await fixtureControl("resume-quiz");
    await fixtureControl("resume-gateway");
  }
  const recovered = commandResponse(page, publicationBase, "POST");
  await page
    .getByRole("button", { name: "Retry same creation", exact: true })
    .click();
  const recoveredHttp = await recovered;
  assertStatus(
    recoveredHttp.status(),
    201,
    "real original create recovery replay",
  );
  const recoveredOwner = (await recoveredHttp.json()) as OwnerView;
  await expect(page).toHaveURL(
    `/app/assessments/${recoveredOwner.publicationId}`,
  );
  expect(creationRequests.length).toBe(2);
  expect(creationRequests[0].key === creationRequests[1].key).toBe(true);
  expect(creationRequests[0].body === creationRequests[1].body).toBe(true);
  expect(
    await fixtureControl("creation-receipt-count", actorMe.body.id, keyDigest),
  ).toBe("1");
  const authoritative = await actor.owner(recoveredOwner.publicationId);
  expect(
    authoritative.configuration.quizVersionId === source.quizVersionId,
  ).toBe(true);
  expect(authoritative.state).toBe("DRAFT");
  await actor.assertConfidentiality();
  evidence(
    info,
    "Real 15-second frontend deadline yields uncertain locked form; zero automatic replay/refresh/logout; explicit retry sends byte-identical payload/key, returns 201 existing resource, single receipt and fresh owner GET 200.",
  );
});
