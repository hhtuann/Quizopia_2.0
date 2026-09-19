import Link from "next/link";
import type { ReactNode } from "react";
import { PageContainer } from "../../components/ui/page-container";
import { Surface } from "../../components/ui/surface";

export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <main id="main-content" tabIndex={-1}>
      <PageContainer className="flex min-h-screen items-center justify-center py-6 sm:py-10 lg:py-12">
        <div className="w-full max-w-md">
          <Link
            className="mx-auto flex min-h-11 w-fit items-center gap-3 rounded-lg px-2 text-sm font-semibold text-foreground transition-colors duration-200 hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 focus-visible:ring-offset-background motion-reduce:transition-none"
            href="/"
          >
            <span
              aria-hidden="true"
              className="flex size-9 items-center justify-center rounded-lg bg-primary text-base font-bold text-foreground-inverse shadow-primary"
            >
              Q
            </span>
            <span>Quizopia 2.0</span>
          </Link>

          <Surface className="mt-5 p-5 sm:p-8">{children}</Surface>
        </div>
      </PageContainer>
    </main>
  );
}
