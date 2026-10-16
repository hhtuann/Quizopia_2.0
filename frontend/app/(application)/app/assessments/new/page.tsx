import type { Metadata } from "next";
import {
  PublicationPage,
  type PublicationSource,
} from "../../../../../features/assessments/components/publication-pages";
import { canonicalUuid } from "../../../../../features/assessments/model/publication";

export const metadata: Metadata = {
  title: "Create Assessment Publication | Quizopia 2.0",
  referrer: "same-origin",
};

export default async function NewPublicationRoute({
  searchParams,
}: {
  readonly searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const query = await searchParams;
  const quizId = typeof query.quizId === "string" ? query.quizId : "";
  const quizVersionId =
    typeof query.quizVersionId === "string" ? query.quizVersionId : "";
  const version =
    typeof query.versionNumber === "string" ? query.versionNumber : "";
  const versionNumber = Number(version);
  const source: PublicationSource | null =
    canonicalUuid.safeParse(quizId).success &&
    canonicalUuid.safeParse(quizVersionId).success &&
    /^[1-9]\d{0,8}$/.test(version) &&
    versionNumber <= 999999999
      ? { quizId, quizVersionId, versionNumber }
      : null;
  return <PublicationPage source={source} />;
}
