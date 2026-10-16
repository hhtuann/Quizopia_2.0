import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createFetchHttpTransport } from "../../../lib/api/http-transport";
import { AuthProvider, useAuth } from "../auth-provider";
import { createAuthSessionService } from "../session/auth-session-service";
import { LoginForm } from "./login-form";

const navigation = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => navigation }));

function SessionProbe() {
  const { user, session, availableWorkspaces } = useAuth();
  return (
    <output aria-label="Session identity">
      {session.status}:{user?.username ?? "none"}:
      {availableWorkspaces.join(",")}
    </output>
  );
}

function harness() {
  let meStatus = 503;
  const fetcher = vi.fn<typeof fetch>(async (target) => {
    const path = String(target);
    if (path.endsWith("/refresh"))
      return Response.json(
        {
          code: "AUTH_REFRESH_FAILED",
          message: "Refresh failed.",
          path: "/api/auth/refresh",
          status: 401,
          traceId: null,
        },
        { status: 401 },
      );
    if (path.endsWith("/login"))
      return Response.json({
        accessToken: "in-memory-candidate",
        expiresIn: 300,
        tokenType: "Bearer",
      });
    if (path.endsWith("/me")) {
      if (meStatus !== 200) return new Response(null, { status: meStatus });
      return Response.json({
        id: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
        email: "learner@gmail.com",
        username: "learner",
        roles: ["STUDENT"],
      });
    }
    if (path.endsWith("/logout")) return new Response(null, { status: 204 });
    throw new Error(`Unexpected request ${path}`);
  });
  const service = createAuthSessionService({
    transport: createFetchHttpTransport(fetcher),
  });
  const view = render(
    <AuthProvider service={service}>
      <LoginForm />
      <SessionProbe />
    </AuthProvider>,
  );
  return {
    fetcher,
    service,
    view,
    setMeStatus: (status: number) => {
      meStatus = status;
    },
  };
}

async function submitLogin() {
  fireEvent.change(screen.getByLabelText("Username or email"), {
    target: { value: "learner" },
  });
  fireEvent.change(screen.getByLabelText("Password"), {
    target: { value: "secret" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
  await screen.findByRole("alert");
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled(),
  );
}

afterEach(() => vi.clearAllMocks());

describe("login hydration UI integration", () => {
  it("shows recovery after /me 503 and navigates only after successful explicit verification", async () => {
    const { fetcher, setMeStatus } = harness();
    await submitLogin();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Account verification is temporarily unavailable",
    );
    expect(screen.getByLabelText("Session identity")).toHaveTextContent(
      "verification-unavailable:none:",
    );
    expect(navigation.replace).not.toHaveBeenCalled();
    expect(document.body).not.toHaveTextContent("in-memory-candidate");
    expect(
      fetcher.mock.calls.some(([url]) => String(url).endsWith("/logout")),
    ).toBe(false);

    setMeStatus(200);
    fireEvent.click(
      screen.getByRole("button", { name: "Retry account verification" }),
    );
    await waitFor(() =>
      expect(navigation.replace).toHaveBeenCalledWith("/app"),
    );
    expect(screen.getByLabelText("Session identity")).toHaveTextContent(
      "authenticated:learner:LEARNING",
    );
    expect(
      fetcher.mock.calls.filter(([url]) => String(url).endsWith("/refresh")),
    ).toHaveLength(1);
    expect(
      fetcher.mock.calls.filter(([url]) => String(url).endsWith("/me")),
    ).toHaveLength(2);
  });

  it("keeps recovery available after repeated failures and honors a subsequent invalid session", async () => {
    const { fetcher, service, setMeStatus } = harness();
    await submitLogin();
    for (let retry = 0; retry < 2; retry += 1) {
      fireEvent.click(
        screen.getByRole("button", { name: "Retry account verification" }),
      );
      await waitFor(() =>
        expect(
          fetcher.mock.calls.filter(([url]) => String(url).endsWith("/me")),
        ).toHaveLength(retry + 2),
      );
      await waitFor(() =>
        expect(
          screen.getByRole("button", { name: "Retry account verification" }),
        ).toBeEnabled(),
      );
    }
    expect(service.runtime.getSnapshot().status).toBe(
      "verification-unavailable",
    );
    expect(
      screen.getByRole("status", { name: "Verification progress" }),
    ).toHaveTextContent("Verification attempt 2 could not be completed");
    expect(
      fetcher.mock.calls.some(([url]) => String(url).endsWith("/logout")),
    ).toBe(false);
    setMeStatus(401);
    fireEvent.click(
      screen.getByRole("button", { name: "Retry account verification" }),
    );
    await waitFor(() =>
      expect(screen.getByLabelText("Session identity")).toHaveTextContent(
        "anonymous:none:",
      ),
    );
    expect(navigation.replace).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Sign in could not be completed",
    );
    expect(
      screen.queryByRole("button", { name: "Retry account verification" }),
    ).not.toBeInTheDocument();
    await waitFor(() =>
      expect(
        fetcher.mock.calls.filter(([url]) => String(url).endsWith("/logout")),
      ).toHaveLength(1),
    );
  });
});

describe("recovery UI lifecycle", () => {
  function deferredResponse() {
    let resolve!: (value: Response) => void;
    let entered!: () => void;
    const started = new Promise<void>((done) => {
      entered = done;
    });
    const pending = new Promise<Response>((done) => {
      resolve = done;
    });
    return { resolve, entered, started, pending };
  }
  const verifiedProfile = () =>
    Response.json({
      id: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
      email: "learner@gmail.com",
      username: "learner",
      roles: ["STUDENT"],
    });

  it("keeps shared pending state across remount and prevents duplicate retry", async () => {
    const h = harness();
    await submitLogin();
    const gate = deferredResponse();
    h.fetcher.mockImplementationOnce(() => {
      gate.entered();
      return gate.pending;
    });
    const firstButton = screen.getByRole("button", {
      name: "Retry account verification",
    });
    firstButton.focus();
    expect(firstButton).toHaveFocus();
    fireEvent.click(firstButton);
    await gate.started;
    expect(
      screen.getByRole("button", { name: "Verifying account" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("status", { name: "Verification progress" }),
    ).toHaveTextContent("Checking your account");
    h.view.unmount();
    render(
      <AuthProvider service={h.service}>
        <LoginForm />
        <SessionProbe />
      </AuthProvider>,
    );
    expect(
      screen.getByRole("button", { name: "Verifying account" }),
    ).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Verifying account" }));
    expect(
      h.fetcher.mock.calls.filter(([url]) => String(url).endsWith("/me")),
    ).toHaveLength(2);
    await act(async () => {
      gate.resolve(verifiedProfile());
    });
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledTimes(1));
    expect(navigation.replace).toHaveBeenCalledWith("/app");
  });

  it("does not navigate from an unmounted login recovery after late success", async () => {
    const h = harness();
    await submitLogin();
    const gate = deferredResponse();
    h.fetcher.mockImplementationOnce(() => {
      gate.entered();
      return gate.pending;
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Retry account verification" }),
    );
    await gate.started;
    h.view.unmount();
    await act(async () => {
      gate.resolve(verifiedProfile());
    });
    await waitFor(() =>
      expect(h.service.runtime.getSnapshot().status).toBe("authenticated"),
    );
    expect(navigation.replace).not.toHaveBeenCalled();
  });
});
