import { z } from "zod";
import type { AuthenticatedRequestExecutor } from "../../../lib/api/authenticated-request";
import { publicApiTarget } from "../../../lib/api/public-api";
import {
  canonicalUuid,
  instantSchema,
} from "../../assessments/model/publication";
import type { PublicationResult } from "../../assessments/api/publication-api-client";
import type {
  ClassroomPage,
  ClassroomSummary,
} from "./classroom-contract.generated";

export type SelectedClassroom = Pick<
  ClassroomSummary,
  "id" | "name" | "subject" | "grade" | "description"
>;
export interface ClassroomApiClient {
  list(
    cursor?: string,
    signal?: AbortSignal,
  ): Promise<PublicationResult<ClassroomPage>>;
  get(
    id: string,
    signal?: AbortSignal,
  ): Promise<PublicationResult<SelectedClassroom>>;
}
const metadata = z.object({
  id: canonicalUuid,
  name: z.string(),
  subject: z.string(),
  grade: z.string(),
  description: z.string().nullable(),
});
const pageSchema = z
  .object({
    items: z
      .array(metadata.extend({ createdAt: instantSchema }).strict())
      .max(100),
    nextCursor: z.string().min(1).max(512).nullable(),
  })
  .strict();

export function createClassroomApiClient(options: {
  readonly authenticatedRequests: AuthenticatedRequestExecutor;
  readonly getSessionGeneration: () => number | null;
  readonly userId: string;
  readonly apiBaseUrl?: string;
}): ClassroomApiClient {
  const generation = options.getSessionGeneration();
  async function read<T>(
    path: string,
    schema: z.ZodType<T>,
    signal?: AbortSignal,
  ): Promise<PublicationResult<T>> {
    if (generation === null || generation !== options.getSessionGeneration())
      return { ok: false, error: { kind: "session" } };
    const timeout = AbortSignal.timeout(15000);
    const combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
    if (combined.aborted) return { ok: false, error: { kind: "aborted" } };
    let removeAbort = () => {};
    const aborted = new Promise<PublicationResult<T>>((resolve) => {
      const listener = () =>
        resolve({
          ok: false,
          error: timeout.aborted
            ? { kind: "unavailable", reason: "timeout" }
            : { kind: "aborted" },
        });
      combined.addEventListener("abort", listener, { once: true });
      removeAbort = () => combined.removeEventListener("abort", listener);
    });
    try {
      return await Promise.race([
        aborted,
        (async (): Promise<PublicationResult<T>> => {
          const result = await options.authenticatedRequests.execute({
            signal: combined,
            createRequest: () => ({
              method: "GET",
              target: publicApiTarget(path, options.apiBaseUrl),
              headers: { accept: "application/json" },
            }),
          });
          if (combined.aborted)
            return { ok: false, error: { kind: "aborted" } };
          if (
            generation !== options.getSessionGeneration() ||
            result.kind === "authentication-failure"
          )
            return { ok: false, error: { kind: "session" } };
          if (result.kind === "refresh-failure")
            return {
              ok: false,
              error: { kind: "unavailable", reason: "refresh" },
            };
          if (result.kind === "transport-error")
            return {
              ok: false,
              error: { kind: "unavailable", reason: "network" },
            };
          const response = result.response;
          if (!response.ok)
            return {
              ok: false,
              error: {
                kind: "api",
                status: response.status,
                code: "CLASSROOM_NOT_ACCESSIBLE",
              },
            };
          const parsed =
            response.status === 200 && response.body.kind === "json"
              ? schema.safeParse(response.body.value)
              : null;
          return parsed?.success
            ? { ok: true, value: parsed.data }
            : { ok: false, error: { kind: "invalid-response" } };
        })().catch((): PublicationResult<T> => ({
          ok: false,
          error: { kind: "unavailable", reason: "network" },
        })),
      ]);
    } finally {
      removeAbort();
    }
  }
  return {
    list(cursor, signal) {
      if (cursor !== undefined && (!cursor.length || cursor.length > 512))
        return Promise.resolve({ ok: false, error: { kind: "invalid-input" } });
      const params = new URLSearchParams({ limit: "20" });
      if (cursor) params.set("cursor", cursor);
      return read(`/classrooms?${params}`, pageSchema, signal);
    },
    get(id, signal) {
      if (!canonicalUuid.safeParse(id).success)
        return Promise.resolve({ ok: false, error: { kind: "invalid-input" } });
      // Existing detail DTO includes ownerTeacherUserId, but no createdAt. Validate
      // its resource/actor binding and retain only the safe selectable metadata.
      return read(
        `/classrooms/${id}`,
        metadata
          .extend({
            id: z.literal(id),
            ownerTeacherUserId: z.literal(options.userId),
          })
          .transform(({ id, name, subject, grade, description }) => ({
            id,
            name,
            subject,
            grade,
            description,
          })),
        signal,
      );
    },
  };
}
