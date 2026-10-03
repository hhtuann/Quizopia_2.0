import Link from "next/link";
import type { ReactNode } from "react";
import { QuizopiaLogo } from "../../components/brand/quizopia-logo";
import { PageContainer } from "../../components/ui/page-container";
import { Surface } from "../../components/ui/surface";

export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <main id="main-content" tabIndex={-1}>
      <PageContainer className="flex min-h-screen items-center justify-center py-6 sm:py-10 lg:py-12">
        <div className="w-full max-w-md">
          <Link
            aria-label="Quizopia home"
            className="mx-auto flex min-h-11 w-fit items-center rounded-lg px-2 transition-colors duration-200 hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 focus-visible:ring-offset-background motion-reduce:transition-none"
            href="/"
          >
            <QuizopiaLogo />
          </Link>

          <Surface className="mt-5 p-5 sm:p-8">{children}</Surface>
        </div>
      </PageContainer>
    </main>
  );
}
