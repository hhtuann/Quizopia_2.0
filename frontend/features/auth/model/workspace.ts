import type { AuthRole } from "./authenticated-user";

export const WORKSPACES = ["LEARNING", "TEACHING"] as const;

export type Workspace = (typeof WORKSPACES)[number];

const noWorkspaces: readonly Workspace[] = Object.freeze([]);
const learningWorkspace: readonly Workspace[] = Object.freeze(["LEARNING"]);
const teachingWorkspace: readonly Workspace[] = Object.freeze(["TEACHING"]);
const allWorkspaces: readonly Workspace[] = Object.freeze([
  "LEARNING",
  "TEACHING",
]);

export function getAvailableWorkspaces(
  roles: readonly AuthRole[],
): readonly Workspace[] {
  const canLearn = roles.includes("STUDENT");
  const canTeach = roles.includes("TEACHER");

  if (canLearn && canTeach) {
    return allWorkspaces;
  }

  if (canLearn) {
    return learningWorkspace;
  }

  if (canTeach) {
    return teachingWorkspace;
  }

  return noWorkspaces;
}

export function deriveDefaultWorkspace(
  roles: readonly AuthRole[],
): Workspace | null {
  const availableWorkspaces = getAvailableWorkspaces(roles);
  return availableWorkspaces[0] ?? null;
}

export function reconcileWorkspace(
  roles: readonly AuthRole[],
  currentWorkspace: Workspace | null,
): Workspace | null {
  const availableWorkspaces = getAvailableWorkspaces(roles);

  if (
    currentWorkspace !== null &&
    availableWorkspaces.includes(currentWorkspace)
  ) {
    return currentWorkspace;
  }

  return availableWorkspaces[0] ?? null;
}
