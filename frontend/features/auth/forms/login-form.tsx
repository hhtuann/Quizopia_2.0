"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { useI18n } from "../../../lib/i18n/provider";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { TextField } from "../../../components/ui/text-field";
import { useAuth } from "../auth-provider";
import { SessionVerificationRecovery } from "../components/session-verification-recovery";

const loginSchema = z.object({
  identifier: z
    .string()
    .max(320, "Username or email must be 320 characters or fewer.")
    .refine(
      (value) => value.trim().length > 0,
      "Enter your username or verified email.",
    ),
  password: z
    .string()
    .refine((value) => value.trim().length > 0, "Enter your password."),
});

type LoginFields = z.infer<typeof loginSchema>;

export function LoginForm() {
  const { t, localize } = useI18n();
  const router = useRouter();
  const { login, session } = useAuth();
  const [serverError, setServerError] = useState<string | null>(null);
  const previousStatus = useRef(session.status);
  useEffect(() => {
    if (previousStatus.current === "verification-unavailable") {
      if (session.status === "authenticated") router.replace("/app");
    }
    previousStatus.current = session.status;
  }, [router, session.status]);
  const {
    formState: { errors, isSubmitting },
    handleSubmit,
    register,
  } = useForm<LoginFields>({
    defaultValues: { identifier: "", password: "" },
    resolver: zodResolver(loginSchema),
  });

  async function onSubmit(fields: LoginFields) {
    setServerError(null);
    const result = await login(fields);
    if (result.ok) {
      router.replace("/app");
      return;
    }

    setServerError(
      result.error.kind === "api-error" &&
        result.error.error.code === "AUTH_INVALID_CREDENTIALS"
        ? "The username/email or password is incorrect."
        : "Sign in could not be completed. Please try again.",
    );
  }

  return (
    <form
      className="mt-7 space-y-5"
      noValidate
      onSubmit={handleSubmit(onSubmit, () => setServerError(null))}
    >
      <TextField
        autoCapitalize="none"
        autoComplete="username"
        error={
          errors.identifier?.message && localize(errors.identifier.message)
        }
        label={t("Username or email")}
        spellCheck={false}
        type="text"
        {...register("identifier")}
      />
      <TextField
        autoComplete="current-password"
        error={errors.password?.message && localize(errors.password.message)}
        label={t("Password")}
        type="password"
        {...register("password")}
      />

      {session.status === "verification-unavailable" ? (
        <SessionVerificationRecovery />
      ) : serverError ? (
        <Alert title={t("Sign in failed")} variant="danger">
          {localize(serverError)}
        </Alert>
      ) : null}

      <Button
        className="w-full"
        isLoading={isSubmitting}
        loadingLabel={t("Signing in")}
        type="submit"
      >
        {t("Sign in")}
      </Button>
    </form>
  );
}
