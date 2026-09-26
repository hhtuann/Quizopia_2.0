"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { TextField } from "../../../components/ui/text-field";

const registrationSchema = z
  .object({
    username: z
      .string()
      .max(255, "Username must be 255 characters or fewer.")
      .refine((value) => value.trim().length > 0, "Enter a username."),
    email: z
      .string()
      .trim()
      .min(1, "Enter an email address.")
      .max(320, "Email address must be 320 characters or fewer.")
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
  const [isIntegrationUnavailable, setIsIntegrationUnavailable] =
    useState(false);
  const {
    formState: { errors },
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

      {isIntegrationUnavailable ? (
        <Alert title="Account creation is not available yet">
          Your information was checked only in this browser and was not sent or
          saved. Please try again when account creation becomes available.
        </Alert>
      ) : null}

      <Button className="w-full" type="submit">
        Create account
      </Button>
    </form>
  );
}
