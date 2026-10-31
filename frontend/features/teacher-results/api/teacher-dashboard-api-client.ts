import type {
  AssessmentFailure,
  AssessmentResult,
  AssessmentRequester,
} from "../../assessments/api/assessment-foundation";
import { createAssessmentRequester } from "../../assessments/api/assessment-foundation";
import { canonicalUuid } from "../../assessments/model/publication";
import {
  studentDirectionSchema,
  studentSortSchema,
  studentStatusFilterSchema,
  teacherStudentHistorySchema,
  teacherStudentOverviewSchema,
  teacherStudentsSchema,
  type StudentDirection,
  type StudentSort,
  type StudentStatusFilter,
  type TeacherStudentHistory,
  type TeacherStudentOverview,
  type TeacherStudents,
} from "../model/teacher-dashboard";

export type {
  AssessmentFailure as TeacherDashboardFailure,
  AssessmentResult as TeacherDashboardResult,
};

/** Only the query keys the aggregate contract allowlists are ever sent. */
export interface StudentDirectoryQuery {
  readonly limit?: number;
  readonly cursor?: string;
  readonly studentId?: string;
  readonly status?: StudentStatusFilter;
  readonly sort?: StudentSort;
  readonly direction?: StudentDirection;
}

export interface StudentHistoryQuery {
  readonly limit?: number;
  readonly cursor?: string;
}

export interface TeacherDashboardApiClient {
  /**
   * GET /publications/{publicationId}/student-overview — full-publication
   * distinct counters, best/average and the exact twenty-bin histogram.
   * Independent of directory filters and page size; never recalculated here.
   */
  studentOverview(
    publicationId: string,
    signal?: AbortSignal,
  ): Promise<AssessmentResult<TeacherStudentOverview>>;

  /**
   * GET /publications/{publicationId}/students — live keyset pages of
   * distinct-student rows. Each row aggregates that student's complete
   * history in this Publication; sorting/filtering stay server-side.
   */
  students(
    publicationId: string,
    query?: StudentDirectoryQuery,
    signal?: AbortSignal,
  ): Promise<AssessmentResult<TeacherStudents>>;

  /**
   * GET /publications/{publicationId}/students/{studentId}/attempts — the
   * student's paginated attempt history, descending ordinal. No answers/keys.
   */
  studentHistory(
    publicationId: string,
    studentId: string,
    query?: StudentHistoryQuery,
    signal?: AbortSignal,
  ): Promise<AssessmentResult<TeacherStudentHistory>>;
}

function directorySearch(
  query: Omit<StudentDirectoryQuery, "limit" | "cursor">,
): Record<string, string> {
  const search: Record<string, string> = {};
  if (query.studentId !== undefined) search.studentId = query.studentId;
  if (query.status !== undefined) search.status = query.status;
  if (query.sort !== undefined) search.sort = query.sort;
  if (query.direction !== undefined) search.direction = query.direction;
  return search;
}

function historySearch(query: StudentHistoryQuery): Record<string, string> {
  const search: Record<string, string> = {};
  if (query.limit !== undefined) search.limit = String(query.limit);
  if (query.cursor !== undefined) search.cursor = query.cursor;
  return search;
}

export function createTeacherDashboardApiClient(options: {
  readonly authenticatedRequests: Parameters<
    typeof createAssessmentRequester
  >[0]["authenticatedRequests"];
  readonly getSessionGeneration: () => number | null;
  readonly apiBaseUrl?: string;
}): TeacherDashboardApiClient {
  const requester: AssessmentRequester = createAssessmentRequester(options);
  const invalid: AssessmentResult<never> = {
    ok: false,
    error: { kind: "invalid-input" },
  };

  return Object.freeze({
    studentOverview(publicationId, signal) {
      if (!canonicalUuid.safeParse(publicationId).success)
        return Promise.resolve(invalid);
      return requester.execute({
        method: "GET",
        path: `/publications/${publicationId}/student-overview`,
        schema: teacherStudentOverviewSchema,
        signal,
      });
    },
    students(publicationId, query, signal) {
      if (
        !canonicalUuid.safeParse(publicationId).success ||
        (query?.studentId !== undefined &&
          !canonicalUuid.safeParse(query.studentId).success) ||
        (query?.status !== undefined &&
          !studentStatusFilterSchema.safeParse(query.status).success) ||
        (query?.sort !== undefined &&
          !studentSortSchema.safeParse(query.sort).success) ||
        (query?.direction !== undefined &&
          !studentDirectionSchema.safeParse(query.direction).success)
      )
        return Promise.resolve(invalid);
      const { limit, cursor, ...filters } = query ?? {};
      return requester.execute({
        method: "GET",
        path: `/publications/${publicationId}/students`,
        schema: teacherStudentsSchema,
        query: { limit, cursor },
        search: directorySearch(filters),
        signal,
      });
    },
    studentHistory(publicationId, studentId, query, signal) {
      if (
        !canonicalUuid.safeParse(publicationId).success ||
        !canonicalUuid.safeParse(studentId).success
      )
        return Promise.resolve(invalid);
      return requester.execute({
        method: "GET",
        path: `/publications/${publicationId}/students/${studentId}/attempts`,
        schema: teacherStudentHistorySchema,
        search: historySearch(query ?? {}),
        signal,
      });
    },
  } satisfies TeacherDashboardApiClient);
}
