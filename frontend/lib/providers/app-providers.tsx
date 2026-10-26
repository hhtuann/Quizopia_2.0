"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { AuthProvider } from "../../features/auth/auth-provider";
import { AttemptEphemeralProvider } from "../../features/attempts/hooks/attempt-ephemeral-context";
import { WorkspaceTransitionProvider } from "../../components/shell/workspace-transition-overlay";
import { I18nProvider } from "../../lib/i18n/provider";
import type { Locale } from "../../lib/i18n/core";

export interface AppProvidersProps {
  children: ReactNode;
  /**
   * Server-resolved locale (cookie, default vi). Passed by the root server
   * layout so SSR and hydration agree without locale flashes or URL changes.
   */
  readonly initialLocale?: Locale;
}

export function AppProviders({ children, initialLocale }: AppProvidersProps) {
  const [queryClient] = useState(() => new QueryClient());

  return (
    <I18nProvider initialLocale={initialLocale}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <AttemptEphemeralProvider>
            <WorkspaceTransitionProvider>
              {children}
            </WorkspaceTransitionProvider>
          </AttemptEphemeralProvider>
        </AuthProvider>
      </QueryClientProvider>
    </I18nProvider>
  );
}
