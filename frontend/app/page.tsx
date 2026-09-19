"use client";

import { useState } from "react";
import { Alert } from "../components/ui/alert";
import { Button } from "../components/ui/button";
import { PageContainer } from "../components/ui/page-container";
import { Surface } from "../components/ui/surface";
import { TextField } from "../components/ui/text-field";

export default function HomePage() {
  const [isChecked, setIsChecked] = useState(false);

  return (
    <main id="main-content" tabIndex={-1}>
      <PageContainer className="flex min-h-screen items-center py-10 sm:py-16">
        <Surface className="mx-auto w-full max-w-2xl p-6 sm:p-8">
          <header className="max-w-xl">
            <p className="text-sm font-semibold text-primary">Quizopia 2.0</p>
            <h1 className="mt-2 text-3xl font-bold tracking-[-0.02em] text-foreground">
              Product interface foundation
            </h1>
            <p className="mt-3 text-base leading-7 text-foreground-secondary">
              This temporary screen verifies the shared visual baseline while
              authentication and product workflows remain deliberately out of
              scope.
            </p>
          </header>

          <section
            aria-labelledby="component-preview-title"
            className="mt-8 border-t border-border pt-8"
          >
            <h2
              id="component-preview-title"
              className="text-lg font-semibold text-foreground"
            >
              Foundation preview
            </h2>

            <div className="mt-5 space-y-5">
              <TextField
                label="Example field"
                helperText="Preview only; no information is submitted."
                placeholder="Inspect the field states"
              />

              <Alert title="Foundation status">
                {isChecked
                  ? "Interaction, focus, and status feedback are working."
                  : "Design tokens and application providers are ready for review."}
              </Alert>

              <div className="flex flex-col gap-3 sm:flex-row">
                <Button onClick={() => setIsChecked(true)}>
                  Check foundation
                </Button>
                <Button
                  disabled={!isChecked}
                  onClick={() => setIsChecked(false)}
                  variant="secondary"
                >
                  Reset preview
                </Button>
              </div>
            </div>
          </section>
        </Surface>
      </PageContainer>
    </main>
  );
}
