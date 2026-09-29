"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { TextField } from "../../../components/ui/text-field";
import { useAuth } from "../auth-provider";

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
  const router = useRouter();
  const { login } = useAuth();
  const [serverError, setServerError] = useState<string | null>(null);
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
        error={errors.identifier?.message}
        label="Username or email"
        spellCheck={false}
        type="text"
        {...register("identifier")}
      />
      <TextField
        autoComplete="current-password"
        error={errors.password?.message}
        label="Password"
        type="password"
        {...register("password")}
      />

      {serverError ? (
        <Alert title="Sign in failed" variant="danger">
          {serverError}
        </Alert>
      ) : null}

      <Button
        className="w-full"
        isLoading={isSubmitting}
        loadingLabel="Signing in"
        type="submit"
      >
        Sign in
      </Button>
    </form>
  );
}
