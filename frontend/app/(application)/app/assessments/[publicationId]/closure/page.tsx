import type { Metadata } from "next";
import { ClosureManagementPage } from "../../../../../../features/closures/components/closure-pages";

export const metadata: Metadata = {
  title: "Closure workflow | Quizopia 2.0",
  referrer: "same-origin",
};

export default async function ClosureManagementRoute({
  params,
}: {
  readonly params: Promise<{ readonly publicationId: string }>;
}) {
  const { publicationId } = await params;
  return <ClosureManagementPage publicationId={publicationId} />;
}
