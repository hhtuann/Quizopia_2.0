"use client";

import type { Workspace } from "../../features/auth/model/workspace";

export interface WorkspaceSwitcherProps {
  readonly activeWorkspace: Workspace | null;
  readonly availableWorkspaces: readonly Workspace[];
  readonly onSwitch: (workspace: Workspace) => boolean;
}

const workspaceLabels: Record<Workspace, string> = {
  LEARNING: "Learning",
  TEACHING: "Teaching",
};

export function WorkspaceSwitcher({
  activeWorkspace,
  availableWorkspaces,
  onSwitch,
}: WorkspaceSwitcherProps) {
  if (availableWorkspaces.length < 2) {
    return null;
  }

  return (
    <div className="border-t border-border bg-surface-muted/60">
      <div className="mx-auto flex w-full max-w-7xl flex-wrap items-center gap-3 px-4 py-3 sm:px-6 lg:px-8">
        <span className="text-sm font-semibold text-foreground-secondary">
          Workspace
        </span>
        <div
          aria-label="Choose workspace"
          className="inline-flex rounded-lg border border-border-strong bg-surface p-1"
          role="group"
        >
          {availableWorkspaces.map((workspace) => {
            const isActive = workspace === activeWorkspace;

            return (
              <button
                aria-pressed={isActive}
                className={`min-h-11 rounded-lg px-3 py-2 text-sm font-semibold transition-colors duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 motion-reduce:transition-none ${
                  isActive
                    ? "bg-primary text-foreground-inverse"
                    : "bg-surface text-foreground-secondary hover:bg-surface-muted hover:text-foreground"
                }`}
                key={workspace}
                onClick={() => onSwitch(workspace)}
                type="button"
              >
                {workspaceLabels[workspace]}
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}
