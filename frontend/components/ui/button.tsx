import { forwardRef, type ButtonHTMLAttributes } from "react";
import { LoadingIndicator } from "./loading-indicator";

export type ButtonVariant =
  | "primary"
  | "secondary"
  | "danger"
  | "brand-outline"
  | "neutral-outline"
  | "ghost"
  | "destructive";
export type ButtonSize = "compact" | "default" | "touch";

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  isLoading?: boolean;
  loadingLabel?: string;
  variant?: ButtonVariant;
  size?: ButtonSize;
}

const variantClasses: Record<ButtonVariant, string> = {
  primary: "quiz-button-primary",
  secondary: "quiz-button-neutral-outline",
  "brand-outline": "quiz-button-brand-outline",
  "neutral-outline": "quiz-button-neutral-outline",
  ghost: "quiz-button-ghost",
  danger:
    "border-danger bg-danger text-foreground-inverse hover:brightness-90 active:brightness-75 disabled:border-danger/20 disabled:bg-danger/10 disabled:text-danger disabled:shadow-none disabled:hover:brightness-100",
  destructive:
    "border-danger bg-danger text-foreground-inverse hover:brightness-90 active:brightness-75 disabled:border-danger/20 disabled:bg-danger/10 disabled:text-danger disabled:shadow-none",
};

const sizeClasses: Record<ButtonSize, string> = {
  compact: "min-h-9 px-3 py-1.5 text-xs",
  default: "min-h-10 px-4 py-2 text-sm",
  touch: "min-h-11 px-5 py-2.5 text-sm",
};

export const PRIMARY_LINK_CLASSES =
  "quiz-button-primary inline-flex min-h-11 items-center justify-center gap-2 rounded-full border px-5 py-2.5 text-sm font-semibold transition-[box-shadow,filter] duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 motion-reduce:transition-none";
export const OUTLINE_LINK_CLASSES =
  "quiz-button-brand-outline inline-flex min-h-11 items-center justify-center gap-2 rounded-full border px-5 py-2.5 text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 motion-reduce:transition-none";

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  function Button(
    {
      children,
      className = "",
      disabled = false,
      isLoading = false,
      loadingLabel = "Loading",
      size = "touch",
      type = "button",
      variant = "primary",
      ...props
    },
    ref,
  ) {
    const isDisabled = disabled || isLoading;

    return (
      <button
        {...props}
        aria-busy={isLoading || undefined}
        className={`relative inline-flex items-center justify-center gap-2 rounded-full border font-semibold transition-[background-color,border-color,color,box-shadow,filter] duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:cursor-not-allowed motion-reduce:transition-none ${sizeClasses[size]} ${variantClasses[variant]} ${className}`}
        disabled={isDisabled}
        ref={ref}
        type={type}
      >
        <span
          aria-hidden={isLoading || undefined}
          className={isLoading ? "invisible" : ""}
        >
          {children}
        </span>
        {isLoading ? (
          <span className="absolute inset-0 flex items-center justify-center">
            <LoadingIndicator label={loadingLabel} size="small" />
          </span>
        ) : null}
      </button>
    );
  },
);

Button.displayName = "Button";
