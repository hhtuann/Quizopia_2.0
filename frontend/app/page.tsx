import Link from "next/link";
import { QuizopiaLogo } from "../components/brand/quizopia-logo";
import {
  OUTLINE_LINK_CLASSES,
  PRIMARY_LINK_CLASSES,
} from "../components/ui/button";
import { PageContainer } from "../components/ui/page-container";

export default function HomePage() {
  return (
    <main id="main-content" tabIndex={-1}>
      <div className="relative isolate overflow-hidden">
        <div
          aria-hidden="true"
          className="pointer-events-none absolute inset-x-0 top-0 -z-10 h-[42rem] bg-[radial-gradient(ellipse_at_75%_15%,rgba(124,58,237,0.12),transparent_50%),radial-gradient(ellipse_at_5%_55%,rgba(79,70,229,0.12),transparent_55%)]"
        />
        <header className="border-b border-border/80 bg-surface/80 backdrop-blur-xl">
          <PageContainer
            width="marketing"
            className="flex min-h-20 flex-wrap items-center justify-between gap-4 py-3"
          >
            <Link
              aria-label="Quizopia home"
              className="inline-flex min-h-11 items-center rounded-lg focus-visible:ring-2 focus-visible:ring-focus"
              href="/"
            >
              <QuizopiaLogo markClassName="size-11" />
            </Link>
            <nav
              aria-label="Public navigation"
              className="flex flex-wrap items-center gap-2 sm:gap-3"
            >
              <Link className={OUTLINE_LINK_CLASSES} href="/login">
                Sign in
              </Link>
              <Link className={PRIMARY_LINK_CLASSES} href="/register">
                Get started <span aria-hidden="true">↗</span>
              </Link>
            </nav>
          </PageContainer>
        </header>
        <PageContainer
          width="marketing"
          className="grid min-h-[min(44rem,85vh)] items-center gap-14 py-16 lg:grid-cols-[1.15fr_0.85fr] lg:py-24"
        >
          <div className="max-w-3xl">
            <span className="inline-flex items-center gap-2 rounded-full border border-primary/20 bg-primary/5 px-4 py-2 text-xs font-bold tracking-wide text-primary">
              <span
                className="size-2 rounded-full bg-success"
                aria-hidden="true"
              />{" "}
              THE NEXT CHAPTER OF LEARNING
            </span>
            <h1 className="mt-7 font-heading text-4xl font-extrabold leading-[1.12] tracking-[-0.045em] text-foreground sm:text-5xl lg:text-6xl xl:text-7xl">
              A smarter space to{" "}
              <span className="brand-gradient-text">learn and teach.</span>
            </h1>
            <p className="mt-6 max-w-xl text-base leading-8 text-foreground-secondary sm:text-lg">
              Welcome to Quizopia 2.0. A thoughtful workspace for learners and
              educators, bringing quiz authoring and learning tools together
              with clarity and confidence.
            </p>
            <div className="mt-9 flex flex-wrap items-center gap-3">
              <Link className={PRIMARY_LINK_CLASSES} href="/register">
                Create your account <span aria-hidden="true">→</span>
              </Link>
              <Link className={OUTLINE_LINK_CLASSES} href="/login">
                Open your workspace
              </Link>
            </div>
            <p className="mt-5 text-sm text-foreground-muted">
              Already part of Quizopia? Sign in to pick up where you left off.
            </p>
          </div>
          <div
            aria-label="Quizopia platform overview"
            className="relative mx-auto w-full max-w-xl lg:max-w-none"
          >
            <div
              aria-hidden="true"
              className="absolute -inset-5 rounded-[2rem] border border-primary/10 bg-gradient-to-br from-primary/10 via-white to-secondary/10 blur-sm"
            />
            <div className="relative overflow-hidden rounded-3xl border border-primary/15 bg-surface p-5 shadow-[0_25px_75px_-25px_rgba(79,70,229,0.28)] sm:p-8">
              <div className="flex items-center justify-between border-b border-border pb-5">
                <div className="flex items-center gap-3">
                  <span
                    className="brand-gradient flex size-11 items-center justify-center rounded-2xl text-xl font-black text-white"
                    aria-hidden="true"
                  >
                    ✦
                  </span>
                  <div>
                    <p className="text-sm font-bold text-foreground">
                      One connected workspace
                    </p>
                    <p className="text-xs text-foreground-muted">
                      Built for your next idea
                    </p>
                  </div>
                </div>
                <span className="rounded-full bg-success/10 px-3 py-1 text-xs font-bold text-emerald-700">
                  Quizopia 2.0
                </span>
              </div>
              <div className="mt-6 grid gap-3 sm:grid-cols-2">
                <div className="rounded-2xl border border-primary/15 bg-primary/5 p-5">
                  <span
                    className="flex size-10 items-center justify-center rounded-xl bg-primary/10 text-primary"
                    aria-hidden="true"
                  >
                    ◇
                  </span>
                  <h2 className="mt-5 text-lg font-bold text-foreground">
                    Learning
                  </h2>
                  <p className="mt-2 text-sm leading-6 text-foreground-secondary">
                    Your journey, your pace, your workspace.
                  </p>
                </div>
                <div className="rounded-2xl border border-secondary/15 bg-secondary/5 p-5">
                  <span
                    className="flex size-10 items-center justify-center rounded-xl bg-secondary/10 text-secondary"
                    aria-hidden="true"
                  >
                    ✎
                  </span>
                  <h2 className="mt-5 text-lg font-bold text-foreground">
                    Teaching
                  </h2>
                  <p className="mt-2 text-sm leading-6 text-foreground-secondary">
                    Create and publish with clarity.
                  </p>
                </div>
              </div>
              <div className="mt-4 rounded-2xl border border-border bg-background px-5 py-4">
                <div className="flex items-center gap-3">
                  <span
                    className="flex size-9 items-center justify-center rounded-lg bg-success/10 text-success"
                    aria-hidden="true"
                  >
                    ✓
                  </span>
                  <div>
                    <p className="text-sm font-bold text-foreground">
                      Built around reliable workflows
                    </p>
                    <p className="mt-1 text-xs leading-5 text-foreground-muted">
                      Thoughtful tools with a clear place for every task.
                    </p>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </PageContainer>
      </div>
      <section
        aria-labelledby="why-quizopia"
        className="border-t border-border bg-surface py-16 sm:py-20"
      >
        <PageContainer width="marketing">
          <div className="max-w-2xl">
            <p className="text-sm font-bold tracking-wide text-primary">
              MADE FOR THE WAY YOU GROW
            </p>
            <h2
              id="why-quizopia"
              className="mt-3 font-heading text-3xl font-extrabold tracking-tight text-foreground sm:text-4xl"
            >
              Everything begins with a better experience.
            </h2>
          </div>
          <div className="mt-9 grid gap-5 md:grid-cols-3">
            {[
              {
                number: "01",
                title: "Learn with clarity",
                description:
                  "One place to build your learning journey with a workspace that stays focused on what matters.",
              },
              {
                number: "02",
                title: "Teach with confidence",
                description:
                  "Create, preview, save, and publish versioned quizzes with a dedicated authoring experience.",
              },
              {
                number: "03",
                title: "Build on trust",
                description:
                  "Clear account roles and dependable publishing workflows keep your work organized.",
              },
            ].map((pillar) => (
              <article
                className="rounded-xl border border-border bg-surface p-7 shadow-card"
                key={pillar.number}
              >
                <span className="brand-gradient-text font-mono text-xs font-bold tracking-widest">
                  {pillar.number} / QUIZOPIA
                </span>
                <h3 className="mt-6 text-xl font-bold text-foreground">
                  {pillar.title}
                </h3>
                <p className="mt-3 text-sm leading-7 text-foreground-secondary">
                  {pillar.description}
                </p>
              </article>
            ))}
          </div>
        </PageContainer>
      </section>
      <footer className="border-t border-border bg-background py-7">
        <PageContainer
          width="marketing"
          className="flex flex-wrap items-center justify-between gap-3 text-sm text-foreground-muted"
        >
          <span>© Quizopia 2.0</span>
          <span>A place to learn, create, and grow.</span>
        </PageContainer>
      </footer>
    </main>
  );
}
