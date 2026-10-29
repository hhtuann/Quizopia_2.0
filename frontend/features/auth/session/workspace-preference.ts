import type { AuthRole } from "../model/authenticated-user";
import {
  WORKSPACES,
  reconcileWorkspace,
  type Workspace,
} from "../model/workspace";

/**
 * Per-account workspace (Learning/Teaching) preference, persisted in
 * localStorage so a page reload (F5) keeps the active persona instead of
 * re-deriving the role default.
 *
 * This is a UI preference ONLY — exactly like the `quizopia-locale` cookie:
 * it stores no credentials, tokens, roles or account data, is never an
 * authorization fact, and every restore is validated against the CURRENT
 * authoritative roles (`reconcileWorkspace`), falling back to the role
 * default when absent, stale or no longer permitted.
 *
 * Keyed by user id so shared browsers never leak a persona across accounts.
 */
const KEY_PREFIX = "quizopia-workspace:";

function key(userId: string): string {
  return KEY_PREFIX + userId;
}

export function readStoredWorkspace(userId: string): Workspace | null {
  try {
    const value = globalThis.localStorage?.getItem(key(userId));
    return WORKSPACES.includes(value as Workspace)
      ? (value as Workspace)
      : null;
  } catch {
    return null;
  }
}

export function storeWorkspace(userId: string, workspace: Workspace): void {
  try {
    globalThis.localStorage?.setItem(key(userId), workspace);
  } catch {
    // Persistence is best-effort; the in-memory persona stays authoritative
    // for the current session when storage is unavailable.
  }
}

/**
 * Restores the persisted persona for `userId`, validated against `roles`.
 * Returns the role-derived default when nothing (valid) is stored.
 */
export function restoreWorkspace(
  userId: string,
  roles: readonly AuthRole[],
): Workspace | null {
  return reconcileWorkspace(roles, readStoredWorkspace(userId));
}
