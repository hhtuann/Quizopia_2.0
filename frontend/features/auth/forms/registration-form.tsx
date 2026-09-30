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

const registrationSchema = z
  .object({
    username: z
      .string()
      .max(255, "Username must be 255 characters or fewer.")
      .refine((value) => value.trim().length > 0, "Enter a username."),
    email: z
      .string()
      .max(320, "Email address must be 320 characters or fewer.")
      .refine((value) => value.length > 0, "Enter an email address.")
      .email("Enter a valid email address."),
    password: z
      .string()
      .refine((value) => value.trim().length > 0, "Enter a password."),
    confirmPassword: z
      .string()
      .refine((value) => value.trim().length > 0, "Confirm your password."),
  })
  .refine((fields) => fields.password === fields.confirmPassword, {
    message: "Passwords do not match.",
    path: ["confirmPassword"],
  });

type RegistrationFields = z.infer<typeof registrationSchema>;

export function RegistrationForm() {
  const router = useRouter();
  const { register: registerAccount } = useAuth();
  const [serverError, setServerError] = useState<string | null>(null);
  const {
    formState: { errors, isSubmitting },
    handleSubmit,
    register,
  } = useForm<RegistrationFields>({
    defaultValues: {
      confirmPassword: "",
      email: "",
      password: "",
      username: "",
    },
    resolver: zodResolver(registrationSchema),
  });

  async function onSubmit(fields: RegistrationFields) {
    setServerError(null);
    const result = await registerAccount({
      email: fields.email,
      password: fields.password,
      username: fields.username,
    });
    if (result.ok) {
      router.push(
        `/verify-email?username=${encodeURIComponent(fields.username)}`,
      );
      return;
    }

    if (result.error.kind === "api-error") {
      if (result.error.error.code === "AUTH_USERNAME_UNAVAILABLE") {
        setServerError(
          "That username is unavailable. Choose another username.",
        );
        return;
      }
      if (result.error.error.code === "INVALID_REQUEST") {
        setServerError("Check your account details and try again.");
        return;
      }
    }
    setServerError(
      "Account creation could not be completed. Please try again.",
    );
  }

  return (
    <form
      className="mt-7 space-y-5"
      noValidate
      onSubmit={handleSubmit(onSubmit, () => setServerError(null))}
    >
      <TextField
        autoComplete="username"
        error={errors.username?.message}
        label="Username"
        {...register("username")}
      />
      <TextField
        autoComplete="email"
        error={errors.email?.message}
        label="Email address"
        type="email"
        {...register("email")}
      />
      <TextField
        autoComplete="new-password"
        error={errors.password?.message}
        label="Password"
        type="password"
        {...register("password")}
      />
      <TextField
        autoComplete="new-password"
        error={errors.confirmPassword?.message}
        label="Confirm password"
        type="password"
        {...register("confirmPassword")}
      />

      {serverError ? (
        <Alert title="Account creation failed" variant="danger">
          {serverError}
        </Alert>
      ) : null}

      <Button
        className="w-full"
        isLoading={isSubmitting}
        loadingLabel="Creating account"
        type="submit"
      >
        Create account
      </Button>
    </form>
  );
}
