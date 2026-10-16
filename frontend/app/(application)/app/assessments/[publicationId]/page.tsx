import type { Metadata } from "next";
import { PublicationPage } from "../../../../../features/assessments/components/publication-pages";

export const metadata: Metadata = {
  title: "Assessment Publication | Quizopia 2.0",
  referrer: "same-origin",
};

export default async function PublicationDetailRoute({
  params,
}: {
  readonly params: Promise<{ readonly publicationId: string }>;
}) {
  const { publicationId } = await params;
  return <PublicationPage publicationId={publicationId} />;
}
