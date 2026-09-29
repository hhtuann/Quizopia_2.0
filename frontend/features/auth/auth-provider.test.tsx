import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { HttpTransport } from "../../lib/api/http-transport";
import { AuthProvider, useAuth, type AuthContextValue } from "./auth-provider";
import { createAuthenticatedUser } from "./model/authenticated-user";
import { createAccessTokenVault } from "./session/access-token-vault";
import { createAuthSessionService } from "./session/auth-session-service";
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
          <p>User: {auth.user?.id ?? "none"}</p>
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
          email: "learner01@gmail.com",
          id: userId,
          roles: ["STUDENT", "TEACHER"],
          username: "learner01",
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

  it("starts the real bootstrap service and publishes the hydrated current user", async () => {
    const transport: HttpTransport = {
      async execute(request) {
        const path = new URL(String(request.target), "https://frontend.example")
          .pathname;
        if (path === "/api/auth/refresh") {
          return {
            kind: "response",
            response: {
              body: {
                kind: "json",
                value: {
                  accessToken: "bootstrap-access",
                  expiresIn: 300,
                  tokenType: "Bearer",
                },
              },
              headers: new Headers(),
              ok: true,
              status: 200,
            },
          };
        }
        if (path === "/api/auth/me") {
          expect(new Headers(request.headers).get("authorization")).toBe(
            "Bearer bootstrap-access",
          );
          return {
            kind: "response",
            response: {
              body: {
                kind: "json",
                value: {
                  email: "learner01@gmail.com",
                  id: userId,
                  roles: ["STUDENT", "TEACHER"],
                  username: "learner01",
                },
              },
              headers: new Headers(),
              ok: true,
              status: 200,
            },
          };
        }
        throw new Error(`Unexpected request: ${String(request.target)}`);
      },
    };
    const service = createAuthSessionService({ transport });

    function Consumer() {
      const auth = useAuth();
      return (
        <p>
          {auth.session.status}:{auth.user?.username ?? "none"}:
          {auth.activeWorkspace ?? "none"}
        </p>
      );
    }

    render(
      <AuthProvider service={service}>
        <Consumer />
      </AuthProvider>,
    );

    expect(screen.getByText("bootstrapping:none:none")).toBeInTheDocument();
    await waitFor(() =>
      expect(
        screen.getByText("authenticated:learner01:LEARNING"),
      ).toBeInTheDocument(),
    );
  });
});
