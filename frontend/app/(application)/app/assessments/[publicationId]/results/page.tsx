import type { Metadata } from "next";
import { TeacherResultsPage } from "../../../../../../features/teacher-results/components/teacher-results-pages";

export const metadata: Metadata = {
  title: "Publication results | Quizopia 2.0",
  referrer: "same-origin",
};

export default async function TeacherResultsRoute({
  params,
}: {
  readonly params: Promise<{ readonly publicationId: string }>;
}) {
  const { publicationId } = await params;
  return <TeacherResultsPage publicationId={publicationId} />;
}
