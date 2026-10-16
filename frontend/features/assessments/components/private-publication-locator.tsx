"use client";

import { useState } from "react";
import { Button } from "../../../components/ui/button";

export function PrivatePublicationLocator({
  locator,
}: {
  readonly locator: string;
}) {
  const [revealed, setRevealed] = useState(false);
  const [status, setStatus] = useState("");
  async function copy() {
    try {
      await navigator.clipboard.writeText(locator);
      setStatus("Private locator copied.");
    } catch {
      setStatus(
        "Clipboard access failed. Reveal the locator and copy it manually.",
      );
    }
  }
  return (
    <section
      className="mt-5 rounded-xl border border-border bg-surface-muted p-4"
      aria-label="Private PUBLIC locator"
    >
      <h2 className="font-semibold">Private PUBLIC locator</h2>
      <p className="mt-1 text-sm text-foreground-muted">
        Owner-only metadata. Keep it private. Authenticated STUDENT authority is
        still required. A Student sharing URL is not available in this workflow.
      </p>
      <div className="mt-3 flex flex-wrap gap-3">
        <Button
          onClick={() => setRevealed((value) => !value)}
          variant="secondary"
        >
          {revealed ? "Hide private locator" : "Reveal private locator"}
        </Button>
        <Button onClick={() => void copy()} variant="secondary">
          Copy private locator
        </Button>
      </div>
      {revealed ? (
        <code className="mt-3 block select-all break-all rounded-lg bg-surface p-3 font-mono text-sm">
          {locator}
        </code>
      ) : null}
      <p className="mt-2 text-sm" role="status">
        {status}
      </p>
    </section>
  );
}
