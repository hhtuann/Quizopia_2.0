"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { AuthProvider } from "../../features/auth/auth-provider";
import { AttemptEphemeralProvider } from "../../features/attempts/hooks/attempt-ephemeral-context";

export interface AppProvidersProps {
  children: ReactNode;
}

export function AppProviders({ children }: AppProvidersProps) {
  const [queryClient] = useState(() => new QueryClient());

  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <AttemptEphemeralProvider>{children}</AttemptEphemeralProvider>
      </AuthProvider>
    </QueryClientProvider>
  );
}
