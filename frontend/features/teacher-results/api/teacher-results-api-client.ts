import type {
  AssessmentFailure,
  AssessmentRequester,
  AssessmentResult,
} from "../../assessments/api/assessment-foundation";
import {
  createAssessmentRequester,
  type PageQuery,
} from "../../assessments/api/assessment-foundation";
import type { TeacherResults } from "../../assessments/api/publication-contract.generated";
import { canonicalUuid } from "../../assessments/model/publication";
import { teacherResultsSchema } from "../model/teacher-results";

export type {
  AssessmentFailure as TeacherResultsFailure,
  AssessmentResult as TeacherResultsResult,
};

export interface TeacherResultsApiClient {
  /**
   * GET /publications/{publicationId}/results — owner-only Attempt rows with
   * exact totals. Only `limit` and the opaque `cursor` are ever sent.
   */
  results(
    publicationId: string,
    query?: PageQuery,
    signal?: AbortSignal,
  ): Promise<AssessmentResult<TeacherResults>>;
}

export function createTeacherResultsApiClient(options: {
  readonly authenticatedRequests: Parameters<
    typeof createAssessmentRequester
  >[0]["authenticatedRequests"];
  readonly getSessionGeneration: () => number | null;
  readonly apiBaseUrl?: string;
}): TeacherResultsApiClient {
  const requester: AssessmentRequester = createAssessmentRequester(options);

  return Object.freeze({
    results(publicationId, query, signal) {
      if (!canonicalUuid.safeParse(publicationId).success)
        return Promise.resolve({
          ok: false,
          error: { kind: "invalid-input" },
        } satisfies AssessmentResult<TeacherResults>);
      return requester.execute({
        method: "GET",
        path: `/publications/${publicationId}/results`,
        schema: teacherResultsSchema,
        query,
        signal,
      });
    },
  } satisfies TeacherResultsApiClient);
}
