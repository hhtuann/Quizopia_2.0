import type { Metadata } from "next";
import { ClosureDetailPage } from "../../../../../../../features/closures/components/closure-pages";

export const metadata: Metadata = {
  title: "Closure request | Quizopia 2.0",
  referrer: "same-origin",
};

export default async function ClosureDetailRoute({
  params,
}: {
  readonly params: Promise<{
    readonly publicationId: string;
    readonly closureRequestId: string;
  }>;
}) {
  const { publicationId, closureRequestId } = await params;
  return (
    <ClosureDetailPage
      closureRequestId={closureRequestId}
      publicationId={publicationId}
    />
  );
}
