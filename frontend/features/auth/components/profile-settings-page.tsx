"use client";

import { useEffect, useRef, useState } from "react";
import { ApplicationShell } from "../../../components/shell/application-shell";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { TextField } from "../../../components/ui/text-field";
import { useI18n } from "../../../lib/i18n/provider";
import { useAuth } from "../auth-provider";
import { displayNameSchema, optionalDisplayName } from "../model/display-name";
import { AuthenticatedBoundary } from "./authenticated-boundary";

function ProfileForm() {
  const { user, getSessionGeneration, updateDisplayName } = useAuth();
  const { t, localize } = useI18n();
  const [name, setName] = useState(user?.displayName ?? "");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const inFlight = useRef(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  return (
    <Surface className="max-w-xl p-6 sm:p-8">
      <h1 className="text-3xl font-bold text-foreground">
        {t("Account settings")}
      </h1>
      <form
        className="mt-6 space-y-5"
        noValidate
        onSubmit={async (event) => {
          event.preventDefault();
          if (inFlight.current) return;
          setSaved(false);
          const parsed = displayNameSchema.safeParse(name);
          if (!parsed.success) {
            setError(parsed.error.issues[0]!.message);
            return;
          }
          setError(null);
          const generation = getSessionGeneration();
          inFlight.current = true;
          setPending(true);
          try {
            const result = await updateDisplayName(optionalDisplayName(name));
            if (!mounted.current || generation !== getSessionGeneration())
              return;
            if (result.ok) {
              setName(optionalDisplayName(name) ?? "");
              setSaved(true);
            } else {
              setError(
                "Your display name could not be saved. Check your connection and try again.",
              );
            }
          } catch {
            if (mounted.current && generation === getSessionGeneration())
              setError(
                "Your display name could not be saved. Check your connection and try again.",
              );
          } finally {
            inFlight.current = false;
            if (mounted.current) setPending(false);
          }
        }}
      >
        <TextField
          autoComplete="nickname"
          disabled={pending}
          label={t("Display name (optional)")}
          helperText={t(
            "A name you provide, not a verified legal name. Teachers may see it in results.",
          )}
          value={name}
          onChange={(event) => {
            setName(event.target.value);
            setSaved(false);
            setError(null);
          }}
        />
        <p className="text-sm text-foreground-secondary">
          {t(
            "Leave empty and save to remove your display name. Your username will be used instead.",
          )}
        </p>
        {error ? (
          <Alert title={t("Display name not saved")} variant="danger">
            {localize(error)}
          </Alert>
        ) : null}
        {saved ? (
          <p role="status" className="text-sm text-success-strong">
            {t("Display name saved.")}
          </p>
        ) : null}
        <Button
          type="submit"
          isLoading={pending}
          loadingLabel={t("Saving display name")}
        >
          {t("Save display name")}
        </Button>
      </form>
    </Surface>
  );
}

export function ProfileSettingsPage() {
  const { user, getSessionGeneration } = useAuth();
  return (
    <AuthenticatedBoundary>
      <ApplicationShell>
        <ProfileForm key={`${user?.id}:${getSessionGeneration()}`} />
      </ApplicationShell>
    </AuthenticatedBoundary>
  );
}
