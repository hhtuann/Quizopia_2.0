"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { TextField } from "../../../components/ui/text-field";

const loginSchema = z.object({
  username: z
    .string()
    .max(255, "Username must be 255 characters or fewer.")
    .refine((value) => value.trim().length > 0, "Enter your username."),
  password: z
    .string()
    .refine((value) => value.trim().length > 0, "Enter your password."),
});

type LoginFields = z.infer<typeof loginSchema>;

export function LoginForm() {
  const [isIntegrationUnavailable, setIsIntegrationUnavailable] =
    useState(false);
  const {
    formState: { errors },
    handleSubmit,
    register,
  } = useForm<LoginFields>({
    defaultValues: { password: "", username: "" },
    resolver: zodResolver(loginSchema),
  });

  return (
    <form
      className="mt-7 space-y-5"
      noValidate
      onSubmit={handleSubmit(
        () => setIsIntegrationUnavailable(true),
        () => setIsIntegrationUnavailable(false),
      )}
    >
      <TextField
        autoComplete="username"
        error={errors.username?.message}
        label="Username"
        type="text"
        {...register("username")}
      />
      <TextField
        autoComplete="current-password"
        error={errors.password?.message}
        label="Password"
        type="password"
        {...register("password")}
      />

      {isIntegrationUnavailable ? (
        <Alert title="Sign-in is not available yet">
          Your sign-in information was not sent or saved. Please try again when
          account access becomes available.
        </Alert>
      ) : null}

      <Button className="w-full" type="submit">
        Sign in
      </Button>
    </form>
  );
}
