import type { Metadata } from "next";
import { TeacherAttemptReviewPage } from "../../../../../../../../features/teacher-results/components/teacher-attempt-review";
export const metadata: Metadata = {
  title: "Submitted review | Quizopia 2.0",
  referrer: "same-origin",
};
export default async function Page({
  params,
}: {
  readonly params: Promise<{
    readonly publicationId: string;
    readonly attemptId: string;
  }>;
}) {
  const { publicationId, attemptId } = await params;
  return (
    <TeacherAttemptReviewPage
      publicationId={publicationId}
      attemptId={attemptId}
    />
  );
}
