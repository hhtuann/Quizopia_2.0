import Link from "next/link";
import type { ReactNode } from "react";
import { QuizopiaLogo } from "../../components/brand/quizopia-logo";
import { PageContainer } from "../../components/ui/page-container";
import { Surface } from "../../components/ui/surface";

export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <main
      id="main-content"
      tabIndex={-1}
      className="relative isolate min-h-screen overflow-hidden"
    >
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(ellipse_at_10%_20%,rgba(79,70,229,0.12),transparent_52%),radial-gradient(ellipse_at_90%_80%,rgba(124,58,237,0.12),transparent_48%)]"
      />
      <PageContainer className="grid min-h-screen items-center gap-12 py-8 lg:grid-cols-2 lg:gap-16 lg:py-12">
        <aside className="hidden max-w-xl justify-self-center lg:block">
          <span className="inline-flex rounded-full border border-primary/20 bg-primary/5 px-4 py-2 text-xs font-bold tracking-wide text-primary">
            WELCOME TO QUIZOPIA 2.0
          </span>
          <p className="mt-8 font-heading text-5xl font-extrabold leading-tight tracking-[-0.045em] text-foreground xl:text-6xl">
            Your next chapter starts{" "}
            <span className="brand-gradient-text">here.</span>
          </p>
          <p className="mt-6 max-w-md text-lg leading-8 text-foreground-secondary">
            A thoughtful space to learn, create, and teach with confidence.
          </p>
          <div className="mt-12 grid gap-3">
            <div className="rounded-xl border border-primary/15 bg-surface/90 p-5 shadow-card">
              <p className="font-semibold text-foreground">
                Built for learners
              </p>
              <p className="mt-1 text-sm leading-6 text-foreground-muted">
                A focused workspace for your learning journey.
              </p>
            </div>
            <div className="ml-8 rounded-xl border border-secondary/15 bg-surface/90 p-5 shadow-card">
              <p className="font-semibold text-foreground">
                Made for educators
              </p>
              <p className="mt-1 text-sm leading-6 text-foreground-muted">
                Author quizzes, preview content, and publish with confidence.
              </p>
            </div>
          </div>
        </aside>
        <div className="mx-auto w-full max-w-md lg:max-w-lg">
          <Link
            aria-label="Quizopia home"
            className="mx-auto flex min-h-11 w-fit items-center rounded-lg px-2 transition-colors duration-200 hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 focus-visible:ring-offset-background motion-reduce:transition-none"
            href="/"
          >
            <QuizopiaLogo />
          </Link>

          <Surface className="mt-6 border-primary/10 p-6 shadow-[0_20px_65px_-25px_rgba(79,70,229,0.25)] sm:p-9">
            {children}
          </Surface>
          <p className="mt-5 text-center text-xs text-foreground-muted">
            Quizopia 2.0 · Learn, create, grow
          </p>
        </div>
      </PageContainer>
    </main>
  );
}
