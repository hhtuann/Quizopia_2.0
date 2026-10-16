import type { Metadata } from "next";
import { StudentReviewPage } from "../../../../../../features/attempts/components/student-assessment-pages";

export const metadata: Metadata = {
  title: "Assessment Review | Quizopia 2.0",
  referrer: "no-referrer",
};

export default async function ReviewRoute({
  params,
}: {
  readonly params: Promise<{ readonly attemptId: string }>;
}) {
  const { attemptId } = await params;
  return <StudentReviewPage attemptId={attemptId} />;
}
