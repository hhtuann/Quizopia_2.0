import { render, screen } from "@testing-library/react";
import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import { useAuth } from "../../features/auth/auth-provider";
import { AppProviders } from "./app-providers";

describe("AppProviders", () => {
  it("provides one stable QueryClient across rerenders", () => {
    const observedClients: QueryClient[] = [];

    function Consumer() {
      observedClients.push(useQueryClient());
      const auth = useAuth();
      return <p>Session: {auth.session.status}</p>;
    }

    const view = render(
      <AppProviders>
        <Consumer />
      </AppProviders>,
    );

    view.rerender(
      <AppProviders>
        <Consumer />
      </AppProviders>,
    );

    expect(observedClients).toHaveLength(2);
    expect(observedClients[1]).toBe(observedClients[0]);
    expect(screen.getByText("Session: bootstrapping")).toBeInTheDocument();
  });
});
