import { act, fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AuthProvider, useAuth, type AuthContextValue } from "./auth-provider";
import { createAuthenticatedUser } from "./model/authenticated-user";
import { createAccessTokenVault } from "./session/access-token-vault";
import { createSessionRuntime } from "./session/session-runtime";

const userId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";

describe("AuthProvider", () => {
  it("exposes session identity and workspace semantics without credentials", () => {
    const vault = createAccessTokenVault();
    const runtime = createSessionRuntime(vault);
    let latestContext: AuthContextValue | null = null;

    function Consumer() {
      const auth = useAuth();
      latestContext = auth;
      return (
        <>
          <p>State: {auth.session.status}</p>
          <p>User: {auth.user?.userId ?? "none"}</p>
          <p>Workspace: {auth.activeWorkspace ?? "none"}</p>
          <button
            onClick={() => auth.switchWorkspace("TEACHING")}
            type="button"
          >
            Use teaching workspace
          </button>
        </>
      );
    }

    render(
      <AuthProvider runtime={runtime}>
        <Consumer />
      </AuthProvider>,
    );

    expect(screen.getByText("State: bootstrapping")).toBeInTheDocument();

    act(() => {
      runtime.establishAuthenticatedSession({
        accessToken: "initial-access-token",
        user: createAuthenticatedUser({
          userId,
          roles: ["STUDENT", "TEACHER"],
        }),
      });
    });

    expect(screen.getByText("State: authenticated")).toBeInTheDocument();
    expect(screen.getByText(`User: ${userId}`)).toBeInTheDocument();
    expect(screen.getByText("Workspace: LEARNING")).toBeInTheDocument();
    expect(latestContext).not.toHaveProperty("accessToken");

    fireEvent.click(
      screen.getByRole("button", { name: "Use teaching workspace" }),
    );
    expect(screen.getByText("Workspace: TEACHING")).toBeInTheDocument();
  });

  it("keeps its default runtime across ordinary provider rerenders", () => {
    function Consumer() {
      const auth = useAuth();
      return (
        <button onClick={auth.clearLocalSession} type="button">
          State: {auth.session.status}
        </button>
      );
    }

    const view = render(
      <AuthProvider>
        <Consumer />
      </AuthProvider>,
    );
    fireEvent.click(screen.getByRole("button"));
    expect(
      screen.getByRole("button", { name: "State: anonymous" }),
    ).toBeInTheDocument();

    view.rerender(
      <AuthProvider>
        <Consumer />
      </AuthProvider>,
    );
    expect(
      screen.getByRole("button", { name: "State: anonymous" }),
    ).toBeInTheDocument();
  });
});
