import type { Metadata } from "next";
import { StudentResultPage } from "../../../../../../features/attempts/components/student-assessment-pages";

export const metadata: Metadata = {
  title: "Assessment Result | Quizopia 2.0",
  referrer: "no-referrer",
};

export default async function ResultRoute({
  params,
}: {
  readonly params: Promise<{ readonly attemptId: string }>;
}) {
  const { attemptId } = await params;
  return <StudentResultPage attemptId={attemptId} />;
}
