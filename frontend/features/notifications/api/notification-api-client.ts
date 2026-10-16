import type {
  AssessmentFailure,
  AssessmentRequester,
  AssessmentResult,
} from "../../assessments/api/assessment-foundation";
import {
  createAssessmentRequester,
  type PageQuery,
} from "../../assessments/api/assessment-foundation";
import type {
  Notification,
  NotificationPage,
} from "../../assessments/api/publication-contract.generated";
import { canonicalUuid } from "../../assessments/model/publication";
import {
  notificationPageSchema,
  notificationSchema,
} from "../model/notification";

export type {
  AssessmentFailure as NotificationFailure,
  AssessmentResult as NotificationResult,
};

export interface NotificationApiClient {
  /**
   * GET /notifications — the current recipient's durable inbox. Only `limit`
   * and the opaque server-issued `cursor` are ever sent; cursors are never
   * decoded, guessed or modified.
   */
  list(
    query?: PageQuery,
    signal?: AbortSignal,
  ): Promise<AssessmentResult<NotificationPage>>;
  /** GET /notifications/{notificationId} — recipient-only detail. */
  get(
    notificationId: string,
    signal?: AbortSignal,
  ): Promise<AssessmentResult<Notification>>;
}

export function createNotificationApiClient(options: {
  readonly authenticatedRequests: Parameters<
    typeof createAssessmentRequester
  >[0]["authenticatedRequests"];
  readonly getSessionGeneration: () => number | null;
  readonly apiBaseUrl?: string;
}): NotificationApiClient {
  const requester: AssessmentRequester = createAssessmentRequester(options);

  const invalid = <T>(): AssessmentResult<T> => ({
    ok: false,
    error: { kind: "invalid-input" },
  });

  return Object.freeze({
    list(query, signal) {
      return requester.execute({
        method: "GET",
        path: "/notifications",
        schema: notificationPageSchema,
        query,
        signal,
      });
    },
    get(notificationId, signal) {
      if (!canonicalUuid.safeParse(notificationId).success)
        return Promise.resolve(invalid());
      return requester
        .execute({
          method: "GET",
          path: `/notifications/${notificationId}`,
          schema: notificationSchema,
          signal,
        })
        .then((result) =>
          result.ok && result.value.notificationId !== notificationId
            ? invalid()
            : result,
        );
    },
  } satisfies NotificationApiClient);
}
