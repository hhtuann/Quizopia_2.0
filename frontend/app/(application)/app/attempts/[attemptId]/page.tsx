import type { Metadata } from "next";
import { StudentAttemptPage } from "../../../../../features/attempts/components/student-assessment-pages";

export const metadata: Metadata = {
  title: "Assessment Attempt | Quizopia 2.0",
  referrer: "no-referrer",
};

export default async function AttemptRoute({
  params,
}: {
  readonly params: Promise<{ readonly attemptId: string }>;
}) {
  const { attemptId } = await params;
  return <StudentAttemptPage attemptId={attemptId} />;
}
