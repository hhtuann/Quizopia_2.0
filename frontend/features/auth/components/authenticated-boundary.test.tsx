import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AuthProvider } from "../auth-provider";
import { createAuthenticatedUser } from "../model/authenticated-user";
import { createAccessTokenVault } from "../session/access-token-vault";
import { createSessionRuntime } from "../session/session-runtime";
import { AuthenticatedBoundary } from "./authenticated-boundary";

const userId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const accessToken = "boundary-test-access-token";

function createHarness() {
  const vault = createAccessTokenVault();
  const runtime = createSessionRuntime(vault);
  const authenticatedInput = {
    accessToken,
    user: createAuthenticatedUser({ userId, roles: ["STUDENT"] }),
  };

  return { authenticatedInput, runtime, vault };
}

function renderBoundary(
  runtime: ReturnType<typeof createSessionRuntime>,
  bootstrappingPresentation?: "loading" | "unavailable",
) {
  return render(
    <AuthProvider runtime={runtime}>
      <AuthenticatedBoundary
        bootstrappingPresentation={bootstrappingPresentation}
      >
        <p>Authenticated shell content</p>
      </AuthenticatedBoundary>
    </AuthProvider>,
  );
}

describe("authenticated UX boundary", () => {
  it("renders an intentional loading state while a real bootstrap can run", () => {
    const { runtime } = createHarness();

    renderBoundary(runtime);

    expect(
      screen.getByRole("heading", { name: "Checking your session" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(
      "Checking your session",
    );
    expect(screen.queryByText(accessToken)).not.toBeInTheDocument();
  });

  it("renders a truthful recoverable state when production bootstrap is unavailable", () => {
    const { runtime } = createHarness();

    renderBoundary(runtime, "unavailable");

    expect(
      screen.getByRole("heading", { name: "Open the Quizopia application" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(
      "Account access is not available yet",
    );
    expect(screen.getByRole("status")).toHaveTextContent(
      "No session was created",
    );
    expect(screen.getByRole("link", { name: "Go to sign in" })).toHaveAttribute(
      "href",
      "/login",
    );
  });

  it("renders auth-required UX for an anonymous session", () => {
    const { runtime } = createHarness();
    runtime.completeBootstrapAsAnonymous();

    renderBoundary(runtime);

    expect(
      screen.getByRole("heading", { name: "Sign in to continue" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(
      "Account access required",
    );
    expect(screen.getByRole("link", { name: "Go to sign in" })).toHaveAttribute(
      "href",
      "/login",
    );
  });

  it("renders children for an authenticated session without exposing its token", () => {
    const { authenticatedInput, runtime } = createHarness();
    runtime.establishAuthenticatedSession(authenticatedInput);

    renderBoundary(runtime);

    expect(screen.getByText("Authenticated shell content")).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(accessToken);
  });

  it("preserves authenticated content while the session refreshes", () => {
    const { authenticatedInput, runtime } = createHarness();
    runtime.establishAuthenticatedSession(authenticatedInput);
    runtime.beginRefreshing();

    renderBoundary(runtime);

    expect(screen.getByText("Authenticated shell content")).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(accessToken);
  });

  it("renders an announced expired-session state with a sign-in path", () => {
    const { authenticatedInput, runtime } = createHarness();
    runtime.establishAuthenticatedSession(authenticatedInput);
    runtime.expireSession();

    renderBoundary(runtime);

    expect(
      screen.getByRole("heading", { name: "Sign in again" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Your session has ended",
    );
    expect(screen.getByRole("link", { name: "Go to sign in" })).toHaveAttribute(
      "href",
      "/login",
    );
    expect(document.body).not.toHaveTextContent(accessToken);
  });
});
